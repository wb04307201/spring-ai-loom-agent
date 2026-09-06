/**
 * M3+ T4.3 — scoped i18n key extraction helper.
 *
 * Loads /i18n/zh-CN.json and /i18n/en-US.json at startup and exposes
 * {@link window.I18N.t} for translation. NOT a full i18n framework:
 * - No plural rules, no message format, no ICU.
 * - No locale negotiation; falls back to zh-CN on fetch failure.
 * - Missing key → key string itself (so unreplaced strings are
 *   visible in the UI as dot.path literals).
 *
 * Usage:
 *   <script src="../i18n/i18n.js"></script>
 *   <span>${I18N.t("market.admin.title")}</span>
 *
 * To add a new key: append to BOTH zh-CN.json and en-US.json with the
 * same dot.path. Missing in one language falls back to the other.
 */
(function () {
  const FALLBACK = "zh-CN";
  const SUPPORTED = ["zh-CN", "en-US"];
  const cache = {};

  // Resolve dict URLs relative to THIS script's own directory (captured
  // synchronously — document.currentScript is null inside async callbacks).
  // A previous relative "../i18n/" fetch resolved against the DOCUMENT url:
  // correct from admin/*.html (one level down) but 404 from index.html, which
  // sits beside i18n/ → it requested /spring/ai/i18n/ instead of
  // /spring/ai/loom/i18n/, so the dicts never loaded and I18N.t echoed raw keys.
  const scriptEl =
    (typeof document !== "undefined" && document.currentScript) ||
    (typeof document !== "undefined" &&
      document.querySelector('script[src$="i18n/i18n.js"]'));
  const BASE =
    scriptEl && scriptEl.src
      ? scriptEl.src.replace(/[^/]*$/, "") // strip "i18n.js" → ".../i18n/"
      : "i18n/";

  async function loadDict(locale) {
    if (cache[locale]) return cache[locale];
    try {
      const resp = await fetch(BASE + locale + ".json", {
        credentials: "include",
      });
      if (!resp.ok) throw new Error("HTTP " + resp.status);
      const dict = await resp.json();
      cache[locale] = dict;
      return dict;
    } catch (_) {
      cache[locale] = {};
      return cache[locale];
    }
  }

  /**
   * Translate a dot.path key.
   *
   * `fallbackOrLocale` is overloaded for caller ergonomics:
   *   - a SUPPORTED locale string ("zh-CN" / "en-US") → translate in that locale
   *   - anything else (a non-empty string) → used as FALLBACK TEXT when the key
   *     is absent from the dictionaries. Existing callers (app.js `_statusLabel`,
   *     market-admin.js announcement badge) pass their intended fallback here.
   *
   * Resolution order: dict[loc] → dict[FALLBACK] → fallbackText → the key itself
   * (the key is returned last so genuinely-untranslated strings are still
   * visible as dot.path literals).
   *
   * @param {string} key e.g. "market.admin.title"
   * @param {string} [fallbackOrLocale] fallback text OR locale override
   * @returns {string}
   */
  function t(key, fallbackOrLocale) {
    const isLocale =
      typeof fallbackOrLocale === "string" && SUPPORTED.includes(fallbackOrLocale);
    const loc =
      (isLocale
        ? fallbackOrLocale
        : (typeof document !== "undefined" &&
            document.documentElement &&
            document.documentElement.lang)) || FALLBACK;
    const fallbackText =
      !isLocale && typeof fallbackOrLocale === "string" && fallbackOrLocale !== ""
        ? fallbackOrLocale
        : null;
    const dict = cache[loc] || {};
    const fallbackDict = loc === FALLBACK ? {} : cache[FALLBACK] || {};
    if (Object.prototype.hasOwnProperty.call(dict, key)) return dict[key];
    if (Object.prototype.hasOwnProperty.call(fallbackDict, key))
      return fallbackDict[key];
    if (fallbackText !== null) return fallbackText;
    return key;
  }

  /**
   * Pre-load both dictionaries; resolves once both are ready.
   * Call before first render that depends on translations.
   */
  async function ready() {
    await Promise.all(SUPPORTED.map(loadDict));
  }

  window.I18N = { t, ready, SUPPORTED, FALLBACK };

  // Kick off dict loading as soon as this script is parsed (fire-and-forget).
  // Previously nothing invoked ready(), so the caches stayed empty and every
  // render that ran before a manual ready() echoed raw dot.path keys (e.g. the
  // market announcement badge showed "market.admin.announcement.badge").
  // Callers that must not race can still await I18N.ready(); with the fallback
  // support in t(), a pre-ready render degrades to readable fallback text.
  ready();
})();
