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

  async function loadDict(locale) {
    if (cache[locale]) return cache[locale];
    try {
      const resp = await fetch(`../i18n/${locale}.json`, {
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
   * Translate a dot.path key. Returns the key itself if neither locale
   * has it (so unreplaced strings show as literals — easier to spot).
   *
   * @param {string} key e.g. "market.admin.title"
   * @param {string} [locale] override locale (defaults to document.documentElement.lang or FALLBACK)
   * @returns {string}
   */
  function t(key, locale) {
    const loc =
      locale ||
      (typeof document !== "undefined" &&
        document.documentElement &&
        document.documentElement.lang) ||
      FALLBACK;
    const dict = cache[loc] || {};
    const fallbackDict = loc === FALLBACK ? {} : cache[FALLBACK] || {};
    if (Object.prototype.hasOwnProperty.call(dict, key)) return dict[key];
    if (Object.prototype.hasOwnProperty.call(fallbackDict, key))
      return fallbackDict[key];
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
})();
