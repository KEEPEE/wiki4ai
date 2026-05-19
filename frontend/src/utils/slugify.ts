/**
 * URL-friendly slug generation utilities.
 * Matches the backend's Document.generateSlug() logic exactly.
 */

/**
 * Generate a URL-friendly slug from a title string.
 * - Converts to lowercase
 * - Transliterates Slovak/Czech diacritics to ASCII equivalents
 * - Strips all remaining non-ASCII characters (e.g., em dash —, ellipsis …)
 * - Normalizes whitespace and dashes
 * 
 * Examples:
 *   "My Document" → "my-document"
 *   "Úvod do Wiki4AI" → "uvod-do-wiki4ai"
 *   "Index — Wiki4AI Overview" → "index-wiki4ai-overview" (em dash stripped)
 */
export function generateSlug(title: string): string {
  if (!title) return '';
  
  let slug = title.toLowerCase();
  
  // Transliterate diacritics to ASCII equivalents
  slug = slug.replace(/[áäàâ]/g, 'a')
    .replace(/č/g, 'c')
    .replace(/[ďđ]/g, 'd')
    .replace(/[éèêë]/g, 'e')
    .replace(/[íìîï]/g, 'i')
    .replace(/[ĺľ]/g, 'l')
    .replace(/ň/g, 'n')
    .replace(/[óòôöõ]/g, 'o')
    .replace(/ŕ/g, 'r')
    .replace(/š/g, 's')
    .replace(/ť/g, 't')
    .replace(/[úùûü]/g, 'u')
    .replace(/[ýỳÿ]/g, 'y')
    .replace(/ž/g, 'z');
  
  // Strip remaining non-ASCII characters, normalize spaces and dashes
  return slug.replace(/[^a-z0-9\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .trim()
    .replace(/^-|-$/g, '');
}
