import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'OryxOS',
  titleTemplate: ':title — OryxOS',
  description: 'Enterprise Agent OS built on Java — run multiple AI agents on your own infrastructure',
  base: '/oryxos/',
  cleanUrls: true,
  appearance: 'force-light',

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.googleapis.com' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' }],
    ['link', { rel: 'stylesheet', href: 'https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@700&display=swap' }],
    ['meta', { name: 'author', content: 'OryxOS' }],
    ['meta', { name: 'keywords', content: 'OryxOS, enterprise agent OS, Java AI agent, Spring Boot agent, multi-agent system, ReAct loop, LLM routing, agent memory, MCP, tool calling, self-hosted AI' }],
    ['meta', { name: 'robots', content: 'index, follow' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'OryxOS' }],
    ['meta', { property: 'og:title', content: 'OryxOS — Enterprise Agent OS' }],
    ['meta', { property: 'og:description', content: 'Enterprise Agent OS built on Java — run multiple AI agents on your own infrastructure' }],
    ['meta', { property: 'og:url', content: 'https://oryxos.robustmq.com' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'OryxOS — Enterprise Agent OS' }],
    ['meta', { name: 'twitter:description', content: 'Enterprise Agent OS built on Java — run multiple AI agents on your own infrastructure' }],
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
          { text: 'GitHub', link: 'https://github.com/oryx-labs/oryxos' },
        ],
        sidebar: {
          '/docs/': [
            {
              text: 'Introduction',
              items: [
                { text: 'What is OryxOS', link: '/docs/what' },
                { text: 'Why OryxOS', link: '/docs/why' },
              ],
            },
            {
              text: 'Getting Started',
              items: [
                { text: 'Quick Start', link: '/docs/quick-start' },
              ],
            },
            {
              text: 'Architecture',
              items: [
                { text: 'Overview', link: '/docs/architecture' },
                { text: 'ReAct Loop', link: '/docs/react-loop' },
                { text: 'Provider', link: '/docs/provider' },
                { text: 'Memory', link: '/docs/memory' },
                { text: 'Tool System', link: '/docs/tool' },
              ],
            },
            {
              text: 'Reference',
              items: [
                { text: 'REST API', link: '/docs/api' },
                { text: 'CLI', link: '/docs/cli' },
                { text: 'Profile YAML', link: '/docs/profile' },
                { text: 'Roadmap', link: '/docs/roadmap' },
              ],
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
          { text: 'GitHub', link: 'https://github.com/oryx-labs/oryxos' },
        ],
        sidebar: {
          '/zh/docs/': [
            {
              text: '介绍',
              items: [
                { text: 'OryxOS 是什么', link: '/zh/docs/what' },
                { text: '为什么选 OryxOS', link: '/zh/docs/why' },
              ],
            },
            {
              text: '快速入门',
              items: [
                { text: '快速开始', link: '/zh/docs/quick-start' },
              ],
            },
            {
              text: '架构设计',
              items: [
                { text: '架构概览', link: '/zh/docs/architecture' },
                { text: 'ReAct 循环', link: '/zh/docs/react-loop' },
                { text: 'Provider 路由', link: '/zh/docs/provider' },
                { text: '记忆系统', link: '/zh/docs/memory' },
                { text: 'Tool 体系', link: '/zh/docs/tool' },
              ],
            },
            {
              text: '参考',
              items: [
                { text: 'REST API', link: '/zh/docs/api' },
                { text: 'CLI 命令', link: '/zh/docs/cli' },
                { text: 'Profile YAML', link: '/zh/docs/profile' },
                { text: '路线图', link: '/zh/docs/roadmap' },
              ],
            },
          ],
        },
      },
    },
  },

  themeConfig: {
    siteTitle: false,
    logo: '/logo.svg',
    socialLinks: [
      { icon: 'github', link: 'https://github.com/oryx-labs/oryxos' },
    ],
  },

  sitemap: {
    hostname: 'https://oryxos.robustmq.com',
  },
})
