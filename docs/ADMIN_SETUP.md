# B2B Archiving (CMIS File Gateway) – Admin Setup Guide

As of 2026-10-19

## Overview

TPM can only archive to a CMIS repository. The CMIS File Gateway, two iFlows in this package, acts as that repository and writes the payloads to a file server instead; admins complete Steps 1–6 below.

```
                                            ┌──────────────────── CMIS File Gateway ────────────────────┐
TPM archiving job ──CMIS, HTTPS + OAuth2──► iFlow 1: CMIS endpoint ──ProcessDirect──► iFlow 2: file writer ──SFTP or SMB──► File server
(agreement with      CloudIntegration_      B2B_Archive_2_File_Step_1                 B2B_Archive_2_File_Step_2               (end user's setup)
 archiving enabled)  B2BArchive             Data Store, 30 days                       unzip, build path + file name
```

iFlow 1 answers every CMIS call itself and forwards only document uploads to iFlow 2, which writes the `.edi` file to the server.

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
| 1a | `integration-flow` | destination `CloudIntegration_B2BArchive` (Step 3) | The archiving job calls iFlow 1 as a technical user. The HTTPS sender only accepts OAuth tokens that carry role `ESBMessaging.send`; the key supplies client id, secret and token URL for that. |
| 1b | `api` | the admin, once, for the activation call (Step 4) | The activation endpoint belongs to the tenant's management API, not to an iFlow, so it needs an API client. |

No SAP Document Management Service instance (`api-sdm-di`) and no repository onboarding are needed: the CMIS File Gateway is the repository.

**1a. Service key for the CMIS File Gateway endpoint (BTP cockpit)**

1. Subaccount → **Services → Instances and Subscriptions → Create**.
2. Service **SAP Process Integration Runtime**, plan `integration-flow`.
3. Parameters: grant type `Client Credentials`, role `ESBMessaging.send` (the BTP standard user role for calling iFlows).
4. Create a **service key** on the instance and note `clientid`, `clientsecret` and `tokenurl`. Store them in a password safe – they go into the destination in Step 3.

**Production:** `ESBMessaging.send` is the standard user role and lets every client with that role call any iFlow on the tenant. In production, use a specific role instead (e.g. `Oauth2.Send`) for both the service instance and the iFlow. The iFlow's role is the externalized parameter `httpsUR` of `B2B_Archive_2_File_Step_1` (set under Configure); it must match the role in service key 1a.

**1b. API client for the activation call (BTP cockpit)**

1. Create a second instance of **SAP Process Integration Runtime**, plan `api`, grant type `Client Credentials`, with the role required by the B2B archiving activation API (see the B2B Scenarios API reference).
2. Create a service key and note `url` (tenant management host, without `-rt`), `clientid`, `clientsecret` and `tokenurl`.

## Step 2 – Import and deploy the iFlows

Deploy Step 2 first, then Step 1, because iFlow 1 hands uploads to iFlow 2 via ProcessDirect.

1. Import the package (or the two artifacts) into **Design → Integrations and APIs**.
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
| HTTP 401/403 in iFlow 1 | service key lacks role `ESBMessaging.send` | recreate the instance with role `ESBMessaging.send` |
| iFlow 1 runs, iFlow 2 never starts | iFlow 2 not deployed or ProcessDirect address differs | deploy iFlow 2, align `/archive/2/smb` |
| iFlow 2 fails in the receiver channel | connection, credentials or security material of the SFTP/SMB adapter | fix the adapter configuration; test it with Connectivity Test |
| Download from the archive returns 404 | by design: the CMIS File Gateway stores no content | read the file from the file server |

Known limit: only the CMIS Browser binding is supported, so generic CMIS clients must use it (not AtomPub).
