// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

/**
 * Remark plugin: convert ```mermaid fenced code blocks into raw
 * <pre class="mermaid"> HTML nodes *before* Starlight's Expressive Code
 * processor touches them. Mermaid.js auto-discovers .mermaid nodes.
 */
function remarkMermaid() {
  const escape = (s) =>
    s
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');

  return (tree) => {
    const walk = (node) => {
      if (!node || typeof node !== 'object') return;
      const children = node.children;
      if (!Array.isArray(children)) return;
      for (let i = 0; i < children.length; i++) {
        const c = children[i];
        if (c && c.type === 'code' && c.lang === 'mermaid') {
          children[i] = {
            type: 'html',
            value: `<pre class="mermaid">${escape(c.value || '')}</pre>`,
          };
        } else {
          walk(c);
        }
      }
    };
    walk(tree);
  };
}

export default defineConfig({
  site: 'https://alpha-prosoft.github.io',
  base: '/edd-core',
  markdown: {
    remarkPlugins: [remarkMermaid],
  },
  integrations: [
    starlight({
      title: 'Hitchaikers guide to Event Driven Desisign (EDD)',
      description: 'The Core (edd-core)',
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/alpha-prosoft/edd-core',
        },
      ],
      customCss: ['./src/styles/mermaid.css'],
      head: [
        {
          tag: 'script',
          attrs: { type: 'module' },
          content: `
            import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';

            const pickTheme = () =>
              document.documentElement.dataset.theme === 'dark'
                ? 'dark'
                : 'default';

            mermaid.initialize({
              startOnLoad: false,
              theme: pickTheme(),
              securityLevel: 'loose',
            });

            const render = async () => {
              const nodes = document.querySelectorAll(
                'pre.mermaid:not([data-processed="true"])'
              );
              if (!nodes.length) return;
              try {
                await mermaid.run({ nodes });
              } catch (err) {
                console.error('Mermaid render failed:', err);
              }
            };

            const rerender = async () => {
              // Reset all processed nodes so they re-render with the new theme.
              document
                .querySelectorAll('pre.mermaid, .mermaid')
                .forEach((n) => {
                  n.removeAttribute('data-processed');
                  if (n.dataset.source) n.innerHTML = n.dataset.source;
                });
              mermaid.initialize({
                startOnLoad: false,
                theme: pickTheme(),
                securityLevel: 'loose',
              });
              await render();
            };

            // Snapshot source once so theme switches can restore it.
            const snapshot = () => {
              document
                .querySelectorAll('pre.mermaid:not([data-source])')
                .forEach((n) => {
                  n.dataset.source = n.textContent || '';
                });
            };

            const boot = async () => {
              snapshot();
              await render();
            };

            if (document.readyState === 'loading') {
              document.addEventListener('DOMContentLoaded', boot);
            } else {
              boot();
            }
            document.addEventListener('astro:page-load', boot);

            // Re-render when Starlight's theme selector flips data-theme.
            const themeObserver = new MutationObserver(() => rerender());
            themeObserver.observe(document.documentElement, {
              attributes: true,
              attributeFilter: ['data-theme'],
            });
          `,
        },
      ],
      sidebar: [
        {
          label: 'Opening',
          items: [
            { label: '1. What EDD Core Is', slug: '01-why-edd' },
          ],
        },
        {
          label: 'Foundations',
          items: [
            { label: '2. Immutable State as History', slug: '02-event-sourcing' },
            { label: '3. Stored State in EDD Core', slug: '03-database-structure' },
          ],
        },
        {
          label: 'Core Primitives',
          items: [
            { label: '4. Commands', slug: '04-commands' },
            { label: '5. Events', slug: '05-events' },
            { label: '6. Effects', slug: '06-effects' },
            { label: '7. Queries', slug: '07-queries' },
          ],
        },
        {
          label: 'System Design',
          items: [
            {
              label: '8. Aggregates & Dependencies',
              slug: '08-aggregates-and-dependencies',
            },
            {
              label: '9. Service-to-Service Communication',
              slug: '09-service-to-service',
            },
            {
              label: '10. Identifiers & Tracing',
              slug: '10-identifiers-and-tracing',
            },
          ],
        },
        {
          label: 'Practice',
          items: [
            { label: '11. Testing Event-Sourced Systems', slug: '11-testing' },
            { label: '12. Glossary', slug: '12-glossary' },
          ],
        },
      ],
    }),
  ],
});
