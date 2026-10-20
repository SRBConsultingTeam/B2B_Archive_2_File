# B2B_Archive_2_File

B2B archiving for **SAP Integration Suite** (B2B Scenarios / Trading Partner Management)
without a real content management system. A CMIS repository, the **CMIS File Gateway**, is implemented as an
integration flow (iFlow) on Cloud Integration; the archived B2B payloads are forwarded to a
plain file server via **SMB or SFTP**.

**Setting it up?** Follow the step-by-step [Admin Setup Guide](docs/ADMIN_SETUP.md).

> **Note:** menu names and field labels follow the generic SAP documentation and may differ
> slightly in your tenant version. Please cross-check with:
> - [Archiving B2B messages from SAP Integration Suite in a CMIS repository (blog)](https://community.sap.com/t5/technology-blog-posts-by-sap/archiving-b2b-messages-from-sap-integration-suite-in-cmis-repository/ba-p/14308874)
> - [Archiving Payload Data (SAP Help)](https://help.sap.com/docs/integration-suite/isuite-trading-partner-management/archiving-payload-data)
> - [B2B Scenarios API – Archiving KPIs](https://api.sap.com/api/B2BScenarios/resource/get_B2BArchivingKeyPerformanceIndicators)

## 1. The idea

SAP Integration Suite can archive the payloads of B2B messages (EDI interchanges, ...), but
it only supports a **CMIS** repository (e.g. SAP Document Management Service) as target.
If a **CMIS** repository is not available, this project could be an alternative, as it stores the B2B-payload as a simple file on a server via SFTP or SMB adapter.

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
| [`docs/ADMIN_SETUP.md`](docs/ADMIN_SETUP.md) | Short setup guide for BTP / Integration Suite admins (service keys, destination, activation, verification) |
| [`LICENSE`](LICENSE) | MIT License |

To install, zip each iFlow folder (its content, with `META-INF/` at the top level) and import
it into an integration package of your Cloud Integration tenant, or upload it with a tool like
FlashPipe. Then follow the [Admin Setup Guide](docs/ADMIN_SETUP.md).

## 3. The two iFlows

### 3.1 `B2B_Archive_2_File_Step_1` – CMIS File Gateway endpoint (step 1)

- **Sender:** HTTPS, path `/archive/2/smb`, user role from the externalized parameter `httpsUR` (see 4.1).
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
  are used depends on the end user's environment and is configured per installation.
  Path/file-name pattern: directory `<target folder>/${property.ArchiveDirectory}`, file name
  `${property.ArchiveFileName}`, with overwrite enabled and directories auto-created.
- **Exception subprocess:** `exceptionHandler`.

## 4. Configuration

The [Admin Setup Guide](docs/ADMIN_SETUP.md) walks through these steps in order.

### 4.1 Prerequisites

1. Deploy both iFlows in the Cloud Integration tenant.
2. Runtime URL of iFlow 1:
   `https://<tenant>.it-cpi<xxx>-rt.cfapps.<region>.hana.ondemand.com/http/archive/2/smb`
3. Create two service instances + keys of **SAP Process Integration Runtime**
   (grant type `Client Credentials`):

   | Key | Plan | Used by | Why |
   |-----|------|---------|-----|
   | 1 | `integration-flow`, role `ESBMessaging.send` | destination `CloudIntegration_B2BArchive` (4.2) | The archiving job calls iFlow 1 as a technical user; the HTTPS sender only accepts OAuth tokens with the role set in `httpsUR`. |
   | 2 | `api` | the admin, once, for the activation call (4.3) | The activation endpoint belongs to the tenant's management API, not to an iFlow. |

   Note `clientid`, `clientsecret` and `tokenurl` (key 2 also `url`, the tenant management
   host without `-rt`). No SAP Document Management Service instance (`api-sdm-di`) or
   repository onboarding is needed: the CMIS File Gateway is the repository.

   **User role:** `ESBMessaging.send` is the BTP standard user role for calling iFlows and
   lets every client with that role call any iFlow on the tenant. In production, use a
   specific role instead (e.g. `Oauth2.Send`) for both service key 1 and the iFlow. The
   iFlow's role is the externalized parameter `httpsUR` of `B2B_Archive_2_File_Step_1`
   (delivered as `Oauth2.Send`); set it under *Configure* to the role of service key 1.

### 4.2 BTP cockpit – destination

In the subaccount of the Integration Suite: **Connectivity → Destinations → New Destination**

| Property | Value |
|----------|-------|
| Name | **`CloudIntegration_B2BArchive`** |
| Type | `HTTP` |
| URL | runtime URL of iFlow 1 (see 4.1) |
| Proxy Type | `Internet` |
| Authentication | `OAuth2ClientCredentials` |
| Client ID / Client Secret | from service key 1 |
| Token Service URL | `tokenurl` from service key 1 (ends in `/oauth/token`) |
| Token Service URL Type | `Dedicated` |
| Additional property `RepositoryId` | `CMISFileGateway` |

Optional property (see "Configuring Destination" in the SAP documentation):

- `PathPrefix = /` – store directly below the root folder. If not set, the job creates a
  dated folder structure `year/month/day` (the CMIS File Gateway supports this).

The CMIS File Gateway accepts any repository id. When the caller sends none, it reports the
value of the externalized parameter `RepositoryId` of `B2B_Archive_2_File_Step_1` (default
`CMISFileGateway`). Use the same value in the destination: folder and document metadata are
stored per repository id, so changing it later starts with an empty repository.

### 4.3 Activate B2B archiving on the tenant (API)

B2B archiving stays off until it is activated once per tenant by an API call; enabling it
in an agreement has no effect before that. Use service key 2 (plan `api`):

```bash
# 1. token
curl -s -u '<clientid>:<clientsecret>' -X POST '<tokenurl>?grant_type=client_credentials'
# 2. activate
curl -s -X POST '<url>/api/v1/activateB2BArchivingConfiguration' -H 'Authorization: Bearer <access_token>'
# 3. later: progress
curl -s '<url>/api/v1/B2BArchivingKeyPerformanceIndicators' -H 'Authorization: Bearer <access_token>'
```

On activation the job also archives payloads from up to 7 days before; failed interchanges
are only retried with the next scheduled run.

> **Open point:** the endpoint is taken from the SAP community blog linked above. Check the
> request body and the required role in the B2B Scenarios API reference before running it.

### 4.4 Integration Suite – B2B Scenarios

1. Make sure archiving was activated (4.3); the archiving job uses the destination
   `CloudIntegration_B2BArchive`.
2. Open the **agreement** of the partner relationship and **add archiving** to it
   (archiving section of the agreement):
   - enable archiving,
   - select the payloads to archive (e.g. inbound/outbound interchange),
   - save and **deploy** the agreement.
3. After the first processed message the archiving job runs. Follow it in the B2B message
   monitor (archiving status) or with the KPI API
   `GET /B2BArchivingKeyPerformanceIndicators` of the *B2B Scenarios* API (api.sap.com).

### 4.5 Configuration parameters in the iFlows

The installation-specific values are **externalized**: maintain them under *Configure* of
the deployed iFlow. Everything else is fixed in the iFlow design.

**`B2B_Archive_2_File_Step_1` (step 1)**

| Where | Parameter | Externalized as | Default |
|-------|-----------|-----------------|---------|
| HTTPS sender | Address | `connectionAddress` | `/archive/2/smb` |
| HTTPS sender | User role | `httpsUR` | e.g. `ESBMessaging.Send` (must match service key 1, see 4.1) |
| HTTPS sender | CSRF protection | `csrf` | off |
| Content modifier "Set RepositoryId" | Default repository id (exchange property `RepositoryId`) | `RepositoryId` | `CMISFileGateway` |
| Integration flow | Allowed headers | – | `objectId\|repositoryId\|Content-Type\|Content-Length\|FileName` |
| Write to DS | Visibility / retention | – | `local` / 30 days |
| ProcessDirect receiver | Address | – | `/archive/2/smb` |

**`B2B_Archive_2_File_Step_2` (step 2)**

| Where | Parameter | Externalized as | Default |
|-------|-----------|-----------------|---------|
| File receiver | Address (host:port) | `sftpAddress` | end-user specific |
| File receiver | Credential name | `sftpAuthentication` | end-user specific |
| File receiver | **Target directory** | `sftpDir` | `<target folder>/${property.ArchiveDirectory}` |
| File receiver | File name | `sftpFilename` | `${property.ArchiveFileName}` |
| File receiver | Proxy type | `sftpProxy` | `none` |
| File receiver | File exists / auto create | – | `Override` / yes |
| `extractData.groovy` | Directory and file name rules | – | see 3.2 |
| ProcessDirect sender | Address | – | `/archive/2/smb` |

The parameter names start with `sftp` because the delivered receiver channel is SFTP.

The file transfer in step 2 uses the **SFTP or SMB adapter**, depending on the end user's
environment. Configure the receiver channel for that environment and create the security
material the adapter needs (Monitor → Security Material).

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
