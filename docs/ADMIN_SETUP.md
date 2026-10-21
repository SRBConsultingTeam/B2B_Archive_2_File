# B2B Archiving (CMIS File Gateway) – Admin Setup Guide

As of 2026-10-21

## Overview

TPM can only archive to a CMIS repository. The CMIS File Gateway, two iFlows in this package, acts as that repository and writes the payloads to a file server instead; admins complete Steps 1–6 below.

```
TPM archiving job
(agreement with archiving enabled)
      │
      │ CMIS, HTTPS + OAuth2
      │ destination CloudIntegration_B2BArchive
      ▼
┌──────────────── CMIS File Gateway ────────────────┐
│ iFlow 1: CMIS endpoint                            │
│   B2B_Archive_2_File_Step_1                       │
│   Data Store, 30 days                             │
│     │                                             │
│     │ ProcessDirect                               │
│     ▼                                             │
│ iFlow 2: file writer                              │
│   B2B_Archive_2_File_Step_2                       │
│   unzip, build path + file name                   │
└───────────────────────────────────────────────────┘
      │
      │ SFTP or SMB
      ▼
File server (end user's setup)
```

iFlow 1 answers every CMIS call itself and forwards only document uploads to iFlow 2, which writes the `.edi` file to the server.

This guide holds all setup steps. The design of the two iFlows is described in the repository's `README.md`.

Menu names and field labels follow the generic SAP documentation and may differ slightly in your tenant release. Cross-check with:

- [Archiving B2B messages from SAP Integration Suite in a CMIS repository (SAP community blog)](https://community.sap.com/t5/technology-blog-posts-by-sap/archiving-b2b-messages-from-sap-integration-suite-in-cmis-repository/ba-p/14308874)
- [Archiving Payload Data (SAP Help)](https://help.sap.com/docs/integration-suite/isuite-trading-partner-management/archiving-payload-data)
- [B2B Scenarios API – Archiving KPIs](https://api.sap.com/api/B2BScenarios/resource/get_B2BArchivingKeyPerformanceIndicators)

## Prerequisites

You need admin access to the BTP subaccount and the Integration Suite tenant, plus a reachable file server account.

- **BTP subaccount:** Subaccount Administrator, to create service instances, service keys and destinations.
- **Integration Suite:** Cloud Integration with the role collections `PI_Administrator` and `PI_Integration_Developer`, and the Trading Partner Management (B2B Scenarios) capability.
- **File server:** a target folder reachable from Cloud Integration via SFTP or SMB, with write access and the right to create subfolders. Protocol, host and credentials depend on the end user's environment.
- **Artifacts:** the integration package with `B2B_Archive_2_File_Step_1` and `B2B_Archive_2_File_Step_2` (from this Git repository or exported as a package).

## Step 1 – Service keys

You need two OAuth clients. Both clients are service keys of **SAP Process Integration Runtime**, but they serve different callers:

| Key | Plan | Used by | Why it is needed |
| --- | --- | --- | --- |
| 1a | `integration-flow` | destination `CloudIntegration_B2BArchive` (Step 3) | The archiving job calls iFlow 1 as a technical user. The HTTPS sender only accepts OAuth tokens that carry the role set in the iFlow's parameter `httpsUR`; the key supplies client id, secret and token URL for that. |
| 1b | `api` | the admin, once, for the activation call (Step 4) | The activation endpoint belongs to the tenant's management API, not to an iFlow, so it needs an API client. |

No SAP Document Management Service instance (`api-sdm-di`) and no repository onboarding are needed: the CMIS File Gateway is the repository.

**1a. Service key for the CMIS File Gateway endpoint (BTP cockpit)**

1. Subaccount → **Services → Instances and Subscriptions → Create**.
2. Service **SAP Process Integration Runtime**, plan `integration-flow`.
3. Parameters: grant type `Client Credentials` and the role that iFlow 1 expects, i.e. the value of its externalized parameter `httpsUR` (delivered as `Oauth2.Send`, see the note below).
4. Create a **service key** on the instance and note `clientid`, `clientsecret` and `tokenurl`. Store them in a password safe – they go into the destination in Step 3.

**User role:** the role in service key 1a and the externalized parameter `httpsUR` of `B2B_Archive_2_File_Step_1` (set under Configure) must match. `ESBMessaging.send` is the BTP standard user role for calling iFlows and works for a quick test, but it lets every client with that role call any iFlow on the tenant. In production, use a specific role instead, e.g. the delivered `Oauth2.Send`, for both the service instance and the iFlow.

**1b. API client for the activation call (BTP cockpit)**

1. Create a second instance of **SAP Process Integration Runtime**, plan `api`, grant type `Client Credentials`, with the role required by the B2B archiving activation API (see the B2B Scenarios API reference).
2. Create a service key and note `url` (tenant management host, without `-rt`), `clientid`, `clientsecret` and `tokenurl`.

## Step 2 – Import and deploy the iFlows

Deploy Step 2 first, then Step 1, because iFlow 1 hands uploads to iFlow 2 via ProcessDirect.

1. Import the package (or the two artifacts) into **Design → Integrations and APIs**. From the Git repository: zip the content of each iFlow folder (with `META-INF/` at the top level) and add it to an integration package as an integration flow, or upload it with a tool like FlashPipe.
2. Configure the receiver channel of `B2B_Archive_2_File_Step_2` for the end user's file server: use the **SFTP** or the **SMB** adapter, depending on their environment, and create the security material that adapter needs (e.g. User Credentials, known hosts). Keep the directory `…/${property.ArchiveDirectory}` and file name `${property.ArchiveFileName}`, with overwrite and directory creation enabled. Address, credential name, directory and file name are externalized parameters (`sftpAddress`, `sftpAuthentication`, `sftpDir`, `sftpFilename`): set them under Configure. To use SMB instead, swap the adapter in the iFlow.
3. Deploy `B2B_Archive_2_File_Step_2`, then `B2B_Archive_2_File_Step_1`. Leave the ProcessDirect address `/archive/2/smb` identical in both.
4. In **Monitor → Manage Integration Content**, copy the endpoint of `B2B_Archive_2_File_Step_1`. It looks like `https://<tenant>.it-cpi<xxx>-rt.cfapps.<region>.hana.ondemand.com/http/archive/2/smb`.

Files land as `<partner>/OUT/<messageType>_<ICN>.edi` for inbound and `<partner>/IN/<messageType>_<ICN>.edi` for outbound interchanges. iFlow 1 keeps folder and document metadata in a Data Store for 30 days; the content itself exists only on the file server.

## Step 3 – BTP destination

The archiving job finds the repository through a destination with the fixed name `CloudIntegration_B2BArchive`. Create it in the Integration Suite subaccount under **Connectivity → Destinations → Create**.

| Property | Value |
| --- | --- |
| Name | `CloudIntegration_B2BArchive` |
| Type | HTTP |
| URL | endpoint of `B2B_Archive_2_File_Step_1` from Step 2 |
| Proxy Type | Internet |
| Authentication | OAuth2ClientCredentials |
| Client ID / Client Secret | `clientid` / `clientsecret` from service key 1a |
| Token Service URL | `tokenurl` from service key 1a (ends in `/oauth/token`) |
| Token Service URL Type | Dedicated |
| Additional property `RepositoryId` | `CMISFileGateway` |
| Additional property `PathPrefix` | `/` (optional; without it the job creates `year/month/day` folders, which the CMIS File Gateway also supports) |

The CMIS File Gateway accepts any repository id. When the caller sends none, it reports the value of the externalized parameter `RepositoryId` of `B2B_Archive_2_File_Step_1` (default `CMISFileGateway`). Use the same value in the destination: folder and document metadata are stored per repository id, so changing it later starts with an empty repository. Use **Check Connection** to confirm the destination gets a token and reaches the endpoint.

## Step 4 – Activate B2B archiving on the tenant (API)

B2B archiving stays off until it is activated once per tenant by an API call; enabling it in an agreement has no effect before that. Use service key 1b.

1. Get a token:

   ```bash
   curl -s -u '<clientid>:<clientsecret>' -X POST '<tokenurl>?grant_type=client_credentials'
   ```

2. Activate archiving on the tenant management host (`url` of key 1b):

   ```bash
   curl -s -X POST '<url>/api/v1/activateB2BArchivingConfiguration' -H 'Authorization: Bearer <access_token>'
   ```

3. Later, check progress with `GET <url>/api/v1/B2BArchivingKeyPerformanceIndicators` (same token).

On activation the job also archives payloads from up to 7 days before. Failed interchanges are not retried until the next scheduled run.

Open point: the endpoint comes from SAP's community blog. Check the request body and required role in the [B2B Scenarios API](https://api.sap.com/api/B2BScenarios) reference before running it.

## Step 5 – Enable archiving per agreement in Trading Partner Management

Archiving is switched on per agreement; nothing in TPM points to the CMIS File Gateway explicitly, it only uses the destination.

1. Open **Trading Partner Management → Agreements** and select the agreement.
2. In the archiving section, enable archiving and choose the payloads to archive (e.g. inbound and outbound interchange).
3. Save and **deploy** the agreement.
4. Repeat for every agreement that should archive.

Menu labels can differ by tenant release. Cross-check with SAP Help: [Archiving Payload Data](https://help.sap.com/docs/integration-suite/isuite-trading-partner-management/archiving-payload-data).

## Step 6 – Verify and troubleshoot

Setup is done when a processed B2B message shows archiving status "archived" and its `.edi` file is on the file server.

- [ ] Send a test interchange through an agreement with archiving enabled.
- [ ] Message monitor: archiving status of the message is successful.
- [ ] Cloud Integration monitor: completed runs of `B2B_Archive_2_File_Step_1` and `B2B_Archive_2_File_Step_2`.
- [ ] File server: file under `<target folder>/<partner>/IN` or `/OUT`.

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Archiving fails right away, no iFlow 1 runs | destination name, URL or token URL wrong, or archiving not activated | check `CloudIntegration_B2BArchive` with Check Connection; repeat Step 4 |
| HTTP 401/403 in iFlow 1 | role of service key 1a differs from `httpsUR` of iFlow 1 | give the instance the role set in `httpsUR`, or set `httpsUR` to the role of the key |
| iFlow 1 runs, iFlow 2 never starts | iFlow 2 not deployed or ProcessDirect address differs | deploy iFlow 2, align `/archive/2/smb` |
| iFlow 2 fails in the receiver channel | connection, credentials or security material of the SFTP/SMB adapter | fix the adapter configuration; test it with Connectivity Test |
| Download from the archive returns 404 | by design: the CMIS File Gateway stores no content | read the file from the file server |

Known limit: only the CMIS Browser binding is supported, so generic CMIS clients must use it (not AtomPub).

## Reference – externalized parameters

The installation-specific values are externalized: maintain them under **Configure** of the deployed iFlow. Everything else is fixed in the iFlow design.

**`B2B_Archive_2_File_Step_1`**

| Where | Parameter | Externalized as | Default |
| --- | --- | --- | --- |
| HTTPS sender | Address | `connectionAddress` | `/archive/2/smb` |
| HTTPS sender | User role | `httpsUR` | `Oauth2.Send` (must match service key 1a) |
| HTTPS sender | CSRF protection | `csrf` | off |
| Content modifier "Set RepositoryId" | Default repository id (exchange property `RepositoryId`) | `RepositoryId` | `CMISFileGateway` |
| Integration flow | Allowed headers | – | `objectId\|repositoryId\|Content-Type\|Content-Length\|FileName` |
| Write to DS | Visibility / retention | – | `local` / 30 days |
| ProcessDirect receiver | Address | – | `/archive/2/smb` |

**`B2B_Archive_2_File_Step_2`**

| Where | Parameter | Externalized as | Default |
| --- | --- | --- | --- |
| File receiver | Address (host:port) | `sftpAddress` | end-user specific |
| File receiver | Credential name | `sftpAuthentication` | end-user specific |
| File receiver | Target directory | `sftpDir` | `<target folder>/${property.ArchiveDirectory}` |
| File receiver | File name | `sftpFilename` | `${property.ArchiveFileName}` |
| File receiver | Proxy type | `sftpProxy` | `none` |
| File receiver | File exists / auto create | – | `Override` / yes |
| ProcessDirect sender | Address | – | `/archive/2/smb` |

The parameter names start with `sftp` because the delivered receiver channel is SFTP.
