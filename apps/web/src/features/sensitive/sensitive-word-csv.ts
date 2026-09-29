import type { MessageKey } from "@/platform/i18n/messages";

export const SENSITIVE_IMPORT_LIMITS = {
  csvFileBytes: 1_000_000,
  textChars: 200_000,
  words: 5_000,
  wordChars: 128,
  requestBodyBytes: 1_000_000,
} as const;

export type SensitiveCsvWord = { word: string; line: number };
export type SensitiveImportIssue = { line: number | null; key: MessageKey; cells?: string[] };
export type SensitiveCsvParseResult = {
  words: SensitiveCsvWord[];
  issues: SensitiveImportIssue[];
  textChars: number;
};
export type SensitiveImportPayload = {
  words: string[];
  category: string;
  severity: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  action: "BLOCK" | "LOG_ONLY";
};

const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/u;
const issue = (line: number | null, key: MessageKey, cells?: string[]): SensitiveImportIssue =>
  cells === undefined ? { line, key } : { line, key, cells };

type CsvRecord = { line: number; fields: string[]; issue: SensitiveImportIssue | null };

export function sensitiveCsvFileSizeIssue(size: number): SensitiveImportIssue | null {
  return size > SENSITIVE_IMPORT_LIMITS.csvFileBytes
    ? issue(null, "sensitiveImportCsvFileTooLarge")
    : null;
}

export function parseSensitiveWordCsv(input: string): SensitiveCsvParseResult {
  const text = input.replace(/^\uFEFF/u, "");
  if (text.length > SENSITIVE_IMPORT_LIMITS.textChars) {
    return { words: [], issues: [issue(null, "sensitiveImportCsvTextTooLong")], textChars: text.length };
  }

  const records: CsvRecord[] = [];
  let field = "";
  let fields: string[] = [];
  let quotedFields: boolean[] = [];
  let line = 1;
  let recordLine = 1;
  let inQuotes = false;
  let afterQuote = false;
  let fieldWasQuoted = false;
  let recordIssue: SensitiveImportIssue | null = null;

  const markRecordInvalid = (key: MessageKey) => {
    if (!recordIssue) recordIssue = issue(recordLine, key);
  };
  const pushField = () => {
    fields.push(field);
    quotedFields.push(fieldWasQuoted);
    field = "";
    fieldWasQuoted = false;
    afterQuote = false;
  };
  const pushRecord = () => {
    pushField();
    const isBlankLine = fields.length === 1 && !quotedFields[0] && /^ *$/u.test(fields[0]);
    if (!isBlankLine) records.push({ line: recordLine, fields, issue: recordIssue });
    fields = [];
    quotedFields = [];
    recordIssue = null;
    recordLine = line;
  };
  const advanceLine = (index: number) =>
    text[index] === "\r" && text[index + 1] === "\n" ? index + 2 : index + 1;

  for (let index = 0; index < text.length;) {
    const char = text[index];
    if (inQuotes) {
      if (char === '"') {
        if (text[index + 1] === '"') {
          field += '"';
          index += 2;
        } else {
          inQuotes = false;
          afterQuote = true;
          index += 1;
        }
        continue;
      }
      if (char === "\r" || char === "\n") {
        field += char === "\r" && text[index + 1] === "\n" ? "\r\n" : char;
        line += 1;
        index = advanceLine(index);
        continue;
      }
      field += char;
      index += 1;
      continue;
    }

    if (char === "\r" || char === "\n") {
      pushRecord();
      line += 1;
      index = advanceLine(index);
      recordLine = line;
      continue;
    }
    if (char === ",") {
      pushField();
      index += 1;
      continue;
    }
    if (char === '"') {
      if (field.length === 0 && !afterQuote) {
        fieldWasQuoted = true;
        inQuotes = true;
      } else {
        markRecordInvalid("sensitiveImportCsvMalformed");
        field += char;
      }
      index += 1;
      continue;
    }
    if (afterQuote) {
      markRecordInvalid("sensitiveImportCsvMalformed");
      afterQuote = false;
    }
    field += char;
    index += 1;
  }

  if (inQuotes) markRecordInvalid("sensitiveImportCsvUnclosedQuote");
  if (fields.length > 0 || field.length > 0 || fieldWasQuoted || recordIssue) pushRecord();

  const issues: SensitiveImportIssue[] = [];
  if (records.length === 0) {
    return { words: [], issues: [issue(null, "sensitiveImportCsvEmpty")], textChars: text.length };
  }

  const header = records[0];
  if (header.issue) issues.push({ ...header.issue, cells: header.fields });
  if (header.fields.length !== 1) {
    issues.push(issue(header.line, "sensitiveImportCsvOneColumnHeader", header.fields));
    return { words: [], issues, textChars: text.length };
  }
  if (header.fields[0].trim() !== "word") {
    issues.push(issue(header.line, "sensitiveImportCsvWordHeaderRequired", header.fields));
    return { words: [], issues, textChars: text.length };
  }

  const words: SensitiveCsvWord[] = [];
  for (const record of records.slice(1)) {
    if (record.issue) {
      issues.push({ ...record.issue, cells: record.fields });
      continue;
    }
    if (record.fields.length !== 1) {
      issues.push(issue(record.line, "sensitiveImportCsvOneColumnRow", record.fields));
      continue;
    }
    const rawWord = record.fields[0];
    if (CONTROL_CHARACTER.test(rawWord)) {
      issues.push(issue(record.line, "sensitiveImportCsvControlCharacter", [rawWord]));
      continue;
    }
    const word = rawWord.trim();
    if (!word) {
      issues.push(issue(record.line, "sensitiveImportCsvEmptyWord", [rawWord]));
      continue;
    }
    if (word.length > SENSITIVE_IMPORT_LIMITS.wordChars) {
      issues.push(issue(record.line, "sensitiveImportCsvWordTooLong", [word]));
      continue;
    }
    // Only the first record is a header; the literal data word "word" is retained.
    words.push({ word, line: record.line });
  }

  if (words.length > SENSITIVE_IMPORT_LIMITS.words) {
    issues.push(issue(words[SENSITIVE_IMPORT_LIMITS.words].line, "sensitiveImportCsvTooManyWords", [words[SENSITIVE_IMPORT_LIMITS.words].word]));
  }
  if (words.length === 0 && issues.length === 0) {
    issues.push(issue(null, "sensitiveImportCsvNoWords"));
  }
  return { words, issues, textChars: text.length };
}

export function decodeAndParseSensitiveWordCsv(bytes: Uint8Array): SensitiveCsvParseResult {
  const sizeIssue = sensitiveCsvFileSizeIssue(bytes.byteLength);
  if (sizeIssue) return { words: [], issues: [sizeIssue], textChars: 0 };
  try {
    return parseSensitiveWordCsv(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  } catch {
    return { words: [], issues: [issue(null, "sensitiveImportCsvInvalidUtf8")], textChars: 0 };
  }
}

export function validateSensitivePaste(text: string): SensitiveCsvParseResult {
  const issues: SensitiveImportIssue[] = [];
  if (text.length > SENSITIVE_IMPORT_LIMITS.textChars) {
    issues.push(issue(null, "sensitiveImportPasteTooLong"));
  }
  const rawWords = text.split(/[\r\n,，;；、]+/u);
  const words = rawWords.map((raw) => raw.trim()).filter(Boolean);
  if (words.length > SENSITIVE_IMPORT_LIMITS.words) {
    issues.push(issue(null, "sensitiveImportCsvTooManyWords", [words[SENSITIVE_IMPORT_LIMITS.words]]));
  }
  rawWords.forEach((raw, index) => {
    if (!raw.trim()) return;
    if (CONTROL_CHARACTER.test(raw)) issues.push(issue(index + 1, "sensitiveImportCsvControlCharacter", [raw]));
    if (raw.trim().length > SENSITIVE_IMPORT_LIMITS.wordChars) {
      issues.push(issue(index + 1, "sensitiveImportCsvWordTooLong", [raw.trim()]));
    }
  });
  return { words: words.map((word, index) => ({ word, line: index + 1 })), issues, textChars: text.length };
}

export function validateSensitiveImportPayload(payload: SensitiveImportPayload): {
  json: string;
  bodyBytes: number;
  issue: SensitiveImportIssue | null;
} {
  const json = JSON.stringify(payload);
  const bodyBytes = new TextEncoder().encode(json).byteLength;
  return {
    json,
    bodyBytes,
    issue: bodyBytes > SENSITIVE_IMPORT_LIMITS.requestBodyBytes
      ? issue(null, "sensitiveImportRequestBodyTooLarge")
      : null,
  };
}
