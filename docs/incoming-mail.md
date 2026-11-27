# Incoming mail for Step 4

In STS Admin → General → Mailbox connection, enter the IMAP server, port, mailbox login, and password or app password. Use **Test connection**, then **Connect mailbox**. The app reads the main `INBOX` over IMAPS and checks about every two minutes. Each PDF, PNG, or JPEG attachment waits in the Step 4 review queue until Operations chooses its job, vessel, and document item. Emails stay unread and are never deleted or answered. Manual upload remains available.

The backend needs one server secret, `IMAP_CREDENTIAL_KEY`, containing a Base64 encoded random 32 byte key. This encrypts mailbox passwords in the database with AES-GCM. Keep the key backed up securely: losing or rotating it makes the saved mailbox password unreadable, requiring a new connection. The password is never returned by the API.

To stop reading mail, use **Disconnect** in the app. The saved credentials remain encrypted for a later reconnection. Attachments already in the review queue remain pending. The poller reads up to the newest 100 messages per cycle and skips inline images, unsupported types, and attachments above 15 MiB. Mailbox settings require company-wide `MASTER.TECHNICAL.WRITE`; the shared review queue requires company-wide `DOCUMENT.WRITE` and normal access to the selected job and branch.

The manual upload and mailbox intake were committed separately, so mailbox code can be reverted without removing manual upload.
