/** Motion constants (seconds). One orchestrated reveal on load: the outcome bars fill left to right, row by row.
 * Everything else changes only when the reader acts. */
export const motion = {
  base: 0.6,
  stagger: 0.12,
  quick: 0.2,
  ease: [0.22, 0.9, 0.3, 1] as const,
};
