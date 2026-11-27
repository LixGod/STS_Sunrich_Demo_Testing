# Incoming mail for Step 4

STS can poll one IMAP mailbox and show its PDF, PNG, and JPEG attachments in the Step 4 review queue. Operators choose the job page, vessel, and request item for **each** attachment before filing. The original emails stay in the mailbox and are not marked as read. Manual upload remains available regardless of mailbox configuration.

Set these environment variables on the backend service:

| Variable | Value |
| --- | --- |
| `IMAP_ENABLED` | `true` after all details are ready; defaults to `false` |
| `IMAP_HOST` | Mail server hostname |
| `IMAP_PORT` | IMAPS port, normally `993` |
| `IMAP_USERNAME` | Mailbox address or server login name |
| `IMAP_PASSWORD` | Mailbox password or app password; store only as a server secret |
| `IMAP_FOLDER` | `INBOX` (default) |
| `IMAP_POLL_MS` | Poll interval in milliseconds (default `120000`) |

The mailbox poller reads up to the newest 100 messages each cycle. Attachments larger than 15 MiB, inline images, and unsupported file types are skipped. Filing requires company-wide `DOCUMENT.WRITE` plus write access to the selected job and branch. This keeps a shared mailbox queue from being exposed to branch-scoped accounts.

To disconnect the mailbox immediately, set `IMAP_ENABLED=false` and restart the backend. The manual upload stays usable. The mailbox feature is in its own Git commit, separate from the manual upload, so its code can be reverted without removing manual upload.
