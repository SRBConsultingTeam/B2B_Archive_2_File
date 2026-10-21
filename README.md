# B2B_Archive_2_File

B2B archiving for **SAP Integration Suite** (B2B Scenarios / Trading Partner Management)
without a real content management system. A CMIS repository, the **CMIS File Gateway**, is implemented as an
integration flow (iFlow) on Cloud Integration; the archived B2B payloads are forwarded to a
plain file server via **SMB or SFTP**.

This README describes the idea and the design. **All setup steps** (service keys, iFlow import and
configuration, destination, activation, agreements, verification) are in the
[Admin Setup Guide](docs/ADMIN_SETUP.md).

## 1. The idea

SAP Integration Suite can archive the payloads of B2B messages (EDI interchanges, ...), but
it only supports a **CMIS** repository (e.g. SAP Document Management Service) as target.
Many customers simply want the documents on a file server.

This repository closes that gap:

```
 B2B Scenarios (agreement with archiving)
        │  CMIS Browser binding (HTTPS, OAuth2)   destination CloudIntegration_B2BArchive
        ▼
 ┌───────────────────────────────┐  ProcessDirect ┌──────────────────────────────┐  SMB or SFTP
 │ iFlow 1:                      │ ─────────────► │ iFlow 2:                     │ ───────► file server
 │  B2B_Archive_2_File_Step_1    │                │  B2B_Archive_2_File_Step_2   │
 │  CMIS File Gateway endpoint   │                │  extract + write file        │
 └───────────────────────────────┘                └──────────────────────────────┘
```

- The archiving job believes it talks to a CMIS repository.
- iFlow 1 answers the CMIS requests (repository info, types, folders, documents, children, ...)
  and keeps the "repository" state (folders, documents, custom types) in a Data Store, so the
  job's dialog (create type, create dated folders `year/month/day`, upload, read back) works
  like on a real DMS.
- Only the payload upload (`createDocument`) is passed on to iFlow 2, which unpacks the
  document and writes it to the file server.

Besides metadata nothing is stored in the CMIS File Gateway – the content ends up on the file
server only. Content download (`cmisselector=content`) is therefore not supported.

## 2. Content of the repository

| Path | Description |
|------|-------------|
| `B2B_Archive_2_File_Step_1/` | iFlow 1 – CMIS File Gateway endpoint (`CMISResponse.groovy`) |
| `B2B_Archive_2_File_Step_2/` | iFlow 2 – extracts the document and writes it to the file server via SMB or SFTP |
| [`docs/ADMIN_SETUP.md`](docs/ADMIN_SETUP.md) | Setup guide for BTP / Integration Suite admins: all installation and configuration steps, parameter reference, troubleshooting |
| [`LICENSE`](LICENSE) | MIT License |

## 3. The two iFlows

### 3.1 `B2B_Archive_2_File_Step_1` – CMIS File Gateway endpoint (step 1)

- **Sender:** HTTPS, path `/archive/2/smb`, user role from the externalized parameter `httpsUR`.
  Allowed incoming headers are restricted to
  `objectId|repositoryId|Content-Type|Content-Length|FileName`
  (prevents CMIS bracket parameters like `propertyId[0]` from becoming headers).
- **Script `CMISResponse.groovy`:** implements the CMIS 1.1 **Browser binding** (JSON):
  `repositoryInfo`, `typeDefinition` / `createType` (e.g. `b2b:document_v2`), `createFolder`,
  `createDocument`, `object`, `children`, `parent(s)`, `allowableActions`, `deleteObject`.
  Behaviour (404/409 answers, allowable actions, paths) is aligned with SAP Document
  Management Service.
- **Data Store ("Write to DS", "Get objectId", "Read all entries"):** persists folders,
  documents and custom types so later lookups and listings work.
- **Routing** (by exchange properties set in the script):
  - `createDocument` → handed over to iFlow 2,
  - `createFolder` / `createType` → Data Store only,
  - everything else → answered directly by the script.
- **ProcessDirect receiver:** `/archive/2/smb`.

### 3.2 `B2B_Archive_2_File_Step_2` – write to file server (step 2)

- **Sender:** ProcessDirect `/archive/2/smb` (called synchronously by iFlow 1, so a
  file-transfer error is returned to the archiving job).
- **Script `extractData.groovy`:**
  - parses the CMIS `multipart/form-data` upload,
  - turns the `propertyId[n]` / `propertyValue[n]` pairs into exchange properties
    (`b2b:interchangeDirection`, `b2b:senderTradingPartnerName`, `b2b:senderMessageType`,
    `b2b:senderInterchangeControlNumber` and the `receiver…` equivalents),
  - unzips the content (the document; a `.json` metadata file is ignored),
  - derives the target:
    - direction `IN`: directory `<senderTradingPartnerName>/OUT`,
      file `<senderMessageType>_<senderInterchangeControlNumber>.edi`
    - direction `OUT`: directory `<receiverTradingPartnerName>/IN`,
      file `<receiverMessageType>_<receiverInterchangeControlNumber>.edi`
- **Receiver:** file server adapter (**SMB or SFTP**). Which adapter, host and credentials
  are used depends on the end user's environment and is configured per installation
  (see the [Admin Setup Guide](docs/ADMIN_SETUP.md)).
  Path/file-name pattern: directory `<target folder>/${property.ArchiveDirectory}`, file name
  `${property.ArchiveFileName}`, with overwrite enabled and directories auto-created.
- **Exception subprocess:** `exceptionHandler`.

## 4. Setup

Installation and configuration are described only in the
[Admin Setup Guide](docs/ADMIN_SETUP.md): prerequisites, service keys, import and deployment of the
iFlows, BTP destination `CloudIntegration_B2BArchive`, activation of B2B archiving on the tenant,
archiving in the agreements, verification, troubleshooting and the reference of the externalized
iFlow parameters.

## 5. Testing

- Apache Chemistry **CMIS Workbench** against the runtime URL – use the **Browser binding**
  (not AtomPub). The CMIS TCK passes without errors.
- The archiving job's dialog was replayed against the CMIS File Gateway and a real SAP Document
  Management Service and the answers were compared.

## 6. Known limitations

- Only the Browser binding is implemented (AtomPub code is disabled at the end of the script).
- Content download is not supported (404); content exists only on the file server.
- Documents are not checked for duplicate names; a second `createType` of an existing type is
  answered with 201 (real DMS: 409).
- Repository state lives in a Data Store with 30 days retention – older entries expire.


## 7. License

Released under the [MIT License](LICENSE). Copyright (c) 2026 SRB Consulting Team GmbH.
