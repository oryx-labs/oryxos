import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'OryxOS',
  titleTemplate: ':title — OryxOS',
  description:
    'One config file defines one agent; one platform runs a fleet — on your own infrastructure.',
  base: '/oryxos/',
  cleanUrls: true,
  appearance: 'dark',

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    [
      'link',
      {
        rel: 'stylesheet',
        href: 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;900&family=JetBrains+Mono:wght@400;600&display=swap',
      },
    ],
    ['meta', { name: 'author', content: 'oryx-labs' }],
    [
      'meta',
      {
        name: 'keywords',
        content:
          'OryxOS, Agent OS, AI agent, Java 21, Spring Boot, ReAct loop, MCP, A2A, multi-agent, self-hosted, enterprise AI',
      },
    ],
    ['meta', { name: 'robots', content: 'index, follow' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'OryxOS' }],
    ['meta', { property: 'og:title', content: 'OryxOS — Distributed AI Agent OS' }],
    [
      'meta',
      {
        property: 'og:description',
        content: 'One config file defines one agent; one platform runs a fleet.',
      },
    ],
    ['meta', { property: 'og:url', content: 'https://oryxos.robustmq.com' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'OryxOS — Distributed AI Agent OS' }],
    [
      'meta',
      {
        name: 'twitter:description',
        content: 'One config file defines one agent; one platform runs a fleet.',
      },
    ],
    ['link', { rel: 'canonical', href: 'https://oryxos.robustmq.com' }],
  ],

  locales: {
    root: {
      label: 'English',
      lang: 'en-US',
      themeConfig: {
        nav: [
          { text: 'Home', link: '/' },
          { text: 'Docs', link: '/docs/what' },
          { text: 'GitHub', link: 'https://github.com/xkmeng/oryxos' },
        ],
        sidebar: {
          '/docs/': [
            {
              text: 'Introduction',
              items: [{ text: 'What is OryxOS', link: '/docs/what' }],
            },
            {
              text: 'Reference',
              items: [{ text: 'REST API', link: '/docs/api' }],
            },
          ],
        },
      },
    },
    zh: {
      label: '中文',
      lang: 'zh-CN',
      link: '/zh/',
      themeConfig: {
        nav: [
          { text: '首页', link: '/zh/' },
          { text: '文档', link: '/zh/docs/what' },
          { text: 'GitHub', link: 'https://github.com/xkmeng/oryxos' },
        ],
        sidebar: {
          '/zh/docs/': [
            {
              text: '介绍',
              items: [{ text: 'OryxOS 是什么', link: '/zh/docs/what' }],
            },
            {
              text: '参考',
              items: [{ text: 'REST API', link: '/zh/docs/api' }],
            },
          ],
        },
      },
    },
  },

  themeConfig: {
    siteTitle: false,
    logo: '/logo.svg',
    socialLinks: [{ icon: 'github', link: 'https://github.com/xkmeng/oryxos' }],
  },

  sitemap: {
    hostname: 'https://oryxos.robustmq.com',
  },
})
