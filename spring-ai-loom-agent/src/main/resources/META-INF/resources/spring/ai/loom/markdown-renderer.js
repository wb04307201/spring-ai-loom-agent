const ALLOWED_TAGS = new Set([
    'a', 'blockquote', 'br', 'code', 'del', 'em', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'hr', 'img', 'li', 'ol', 'p', 'pre', 'strong', 'table', 'tbody', 'td', 'th', 'thead',
    'tr', 'ul'
]);

const GLOBAL_ATTRIBUTES = new Set(['class', 'id', 'title', 'aria-label']);
const TAG_ATTRIBUTES = {
    a: new Set(['href', 'target', 'rel']),
    img: new Set(['src', 'alt', 'width', 'height']),
    td: new Set(['colspan', 'rowspan']),
    th: new Set(['colspan', 'rowspan'])
};

function isSafeUrl(value, tagName) {
    const normalized = String(value || '').replace(/[\x00-\x20]+/g, '').toLowerCase();
    if (!normalized || /^(?:javascript|vbscript|data):/.test(normalized)) return false;
    if (tagName === 'img') return /^(?:https?:|blob:)/.test(normalized);
    return /^(?:https?:|mailto:|tel:|#|\/|\.\/|\.\.\/)/.test(normalized);
}

/**
 * Sanitize HTML produced by marked before it is assigned to innerHTML.
 * The allowlist deliberately excludes scriptable elements, inline styles,
 * event attributes, SVG and MathML. Unknown elements are reduced to text.
 */
export function sanitizeHtml(html) {
    const template = document.createElement('template');
    template.innerHTML = String(html ?? '');

    for (const element of [...template.content.querySelectorAll('*')]) {
        const tagName = element.tagName.toLowerCase();
        if (!ALLOWED_TAGS.has(tagName)) {
            element.replaceWith(document.createTextNode(element.textContent || ''));
            continue;
        }

        for (const attribute of [...element.attributes]) {
            const name = attribute.name.toLowerCase();
            const allowed = GLOBAL_ATTRIBUTES.has(name)
                || TAG_ATTRIBUTES[tagName]?.has(name);
            if (!allowed || name.startsWith('on')) {
                element.removeAttribute(attribute.name);
                continue;
            }
            if ((name === 'href' || name === 'src') && !isSafeUrl(attribute.value, tagName)) {
                element.removeAttribute(attribute.name);
            }
        }
    }
    return template.innerHTML;
}
