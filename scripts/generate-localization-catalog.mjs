import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourcePath = path.join(root, "apps/web/src/platform/i18n/messages.ts");
const localeDirectory = path.join(root, "apps/web/src/platform/i18n/messages");
const outputPath = path.resolve(process.argv[2] ?? path.join(root, "backend/target/generated-resources/localization/builtin-messages.json"));
const source = readFileSync(sourcePath, "utf8");
const localeDeclaration = /export\s+const\s+staticLocales\s*=\s*(\[[^\]]*\])\s+as\s+const\b/u.exec(source);
if (!localeDeclaration) throw new Error("Could not locate the static locale declaration");
const staticLocales = JSON.parse(localeDeclaration[1]);
if (!Array.isArray(staticLocales) || staticLocales.length === 0 || staticLocales.some((locale) => typeof locale !== "string")) {
  throw new Error("Static locales must be a non-empty string list");
}

class LiteralObjectReader {
  constructor(text, start) {
    this.text = text;
    this.index = start;
  }

  fail(detail) {
    throw new Error(`Unsupported messages.ts dictionary at byte ${this.index}: ${detail}`);
  }

  skipTrivia() {
    while (this.index < this.text.length) {
      if (/\s/u.test(this.text[this.index])) {
        this.index++;
      } else if (this.text.startsWith("//", this.index)) {
        const end = this.text.indexOf("\n", this.index + 2);
        this.index = end < 0 ? this.text.length : end + 1;
      } else if (this.text.startsWith("/*", this.index)) {
        const end = this.text.indexOf("*/", this.index + 2);
        if (end < 0) this.fail("unterminated block comment");
        this.index = end + 2;
      } else {
        return;
      }
    }
  }

  consume(value) {
    this.skipTrivia();
    if (!this.text.startsWith(value, this.index)) this.fail(`expected ${JSON.stringify(value)}`);
    this.index += value.length;
  }

  string() {
    this.skipTrivia();
    const quote = this.text[this.index];
    if (quote !== "'" && quote !== '"') this.fail("expected a quoted string literal");
    this.index++;
    let result = "";
    while (this.index < this.text.length) {
      const char = this.text[this.index++];
      if (char === quote) return result;
      if (char !== "\\") {
        if (char === "\n" || char === "\r") this.fail("newline in string literal");
        result += char;
        continue;
      }
      if (this.index >= this.text.length) this.fail("unfinished string escape");
      const escaped = this.text[this.index++];
      const simple = { n: "\n", r: "\r", t: "\t", b: "\b", f: "\f", v: "\v", "0": "\0", "\\": "\\", "'": "'", '"': '"' };
      if (Object.hasOwn(simple, escaped)) {
        result += simple[escaped];
      } else if (escaped === "\n") {
        // JavaScript line continuation contributes no character.
      } else if (escaped === "\r") {
        if (this.text[this.index] === "\n") this.index++;
      } else if (escaped === "x") {
        result += String.fromCodePoint(this.hex(2));
      } else if (escaped === "u") {
        if (this.text[this.index] === "{") {
          const close = this.text.indexOf("}", this.index + 1);
          if (close < 0) this.fail("invalid Unicode escape");
          const digits = this.text.slice(this.index + 1, close);
          if (!/^[0-9a-fA-F]{1,6}$/u.test(digits)) this.fail("invalid Unicode escape");
          result += String.fromCodePoint(Number.parseInt(digits, 16));
          this.index = close + 1;
        } else {
          result += String.fromCodePoint(this.hex(4));
        }
      } else {
        // Identity escapes are accepted by JavaScript string literals.
        result += escaped;
      }
    }
    this.fail("unterminated string literal");
  }

  hex(length) {
    const digits = this.text.slice(this.index, this.index + length);
    if (!new RegExp(`^[0-9a-fA-F]{${length}}$`, "u").test(digits)) this.fail("invalid hexadecimal escape");
    this.index += length;
    return Number.parseInt(digits, 16);
  }

  propertyName() {
    this.skipTrivia();
    if (this.text[this.index] === "'" || this.text[this.index] === '"') return this.string();
    const match = /^[A-Za-z_$][A-Za-z0-9_$]*/u.exec(this.text.slice(this.index));
    if (!match) this.fail("computed or unsupported property name");
    this.index += match[0].length;
    return match[0];
  }

  stringDictionary(locale, separatorMessage) {
    this.consume("{");
    const messages = Object.create(null);
    while (true) {
      this.skipTrivia();
      if (this.text[this.index] === "}") {
        this.index++;
        break;
      }
      const key = this.propertyName();
      if (Object.hasOwn(messages, key)) this.fail(`duplicate key ${key} in ${locale}`);
      this.consume(":");
      messages[key] = this.string();
      this.skipTrivia();
      if (this.text[this.index] === ",") this.index++;
      else if (this.text[this.index] !== "}") this.fail(separatorMessage);
    }
    if (Object.keys(messages).length === 0) this.fail(`empty locale dictionary ${locale}`);
    return messages;
  }

  localeDictionary(locale) {
    const messages = this.stringDictionary(locale, `expected comma or end of ${locale} dictionary`);
    this.skipTrivia();
    if (!/^as\s+const\b/u.test(this.text.slice(this.index))) this.fail(`${locale} must remain an "as const" string dictionary`);
    return messages;
  }

  dictionary() {
    this.consume("{");
    const locales = Object.create(null);
    while (true) {
      this.skipTrivia();
      if (this.text[this.index] === "}") {
        this.index++;
        break;
      }
      const locale = this.propertyName();
      if (Object.hasOwn(locales, locale)) this.fail(`duplicate locale ${locale}`);
      this.consume(":");
      locales[locale] = this.stringDictionary(locale, "expected comma or end of locale dictionary");
      this.skipTrivia();
      if (this.text[this.index] === ",") this.index++;
      else if (this.text[this.index] !== "}") this.fail("expected comma or end of messages dictionary");
    }
    this.skipTrivia();
    if (!/^as\s+const\b/u.test(this.text.slice(this.index))) this.fail("messages must remain an `as const` string dictionary");
    if (Object.keys(locales).length === 0) this.fail("empty messages dictionary");
    const [sourceLocale] = Object.keys(locales).filter((locale) => locale === "en");
    if (!sourceLocale) this.fail("English source locale is missing");
    const keys = new Set(Object.keys(locales[sourceLocale]));
    for (const [locale, messages] of Object.entries(locales)) {
      const unknown = Object.keys(messages).find((key) => !keys.has(key));
      if (unknown) this.fail(`locale ${locale} contains a key not defined by ${sourceLocale}: ${unknown}`);
      const missing = [...keys].find((key) => !Object.hasOwn(messages, key));
      if (missing) this.fail("locale " + locale + " is missing a key defined by " + sourceLocale + ": " + missing);
    }
    return { sourceLocale, locales, count: keys.size };
  }
}

const localeFiles = readdirSync(localeDirectory)
  .filter((file) => file.endsWith(".ts"))
  .map((file) => file.slice(0, -3))
  .sort();
const expectedLocaleFiles = staticLocales.map((locale) => locale.toLowerCase()).sort();
if (JSON.stringify(expectedLocaleFiles) !== JSON.stringify(localeFiles)) {
  throw new Error(`Static locale list does not match message modules: ${localeFiles.join(", ")}`);
}
const locales = Object.create(null);
for (const locale of staticLocales) {
  const localeSource = readFileSync(path.join(localeDirectory, `${locale.toLowerCase()}.ts`), "utf8");
  const declaration = /export\s+const\s+messages\s*=\s*/u.exec(localeSource);
  if (!declaration) throw new Error(`Could not locate the ${locale} messages declaration`);
  const reader = new LiteralObjectReader(localeSource, declaration.index + declaration[0].length);
  locales[locale] = reader.localeDictionary(locale);
}
const sourceLocale = staticLocales.includes("en") ? "en" : null;
if (!sourceLocale) throw new Error("English source locale is missing");
const keys = new Set(Object.keys(locales[sourceLocale]));
for (const [locale, messages] of Object.entries(locales)) {
  const unknown = Object.keys(messages).find((key) => !keys.has(key));
  if (unknown) throw new Error(`Locale ${locale} contains a key not defined by ${sourceLocale}: ${unknown}`);
  const missing = [...keys].find((key) => !Object.hasOwn(messages, key));
  if (missing) throw new Error(`Locale ${locale} is missing a key defined by ${sourceLocale}: ${missing}`);
}
const catalog = { sourceLocale, locales, count: keys.size };
const payload = {
  formatVersion: 1,
  sourceLocale: catalog.sourceLocale,
  keyCount: catalog.count,
  locales: catalog.locales,
};
mkdirSync(path.dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(payload)}\n`);
console.log(`Generated ${catalog.count} localization keys for ${Object.keys(catalog.locales).length} static locales`);
