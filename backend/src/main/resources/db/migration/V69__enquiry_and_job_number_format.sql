UPDATE number_series_format SET mask = 'ENQ-STS/{BRANCH}/{DDMMYYYY}/{SEQ:3}' WHERE series_type = 'ENQUIRY';
UPDATE number_series_format SET mask = 'JOB-STS/{BRANCH}/{DDMMYYYY}/{SEQ:3}' WHERE series_type = 'JOB';
