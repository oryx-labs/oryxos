import DefaultTheme from 'vitepress/theme'
import './custom.css'
import Home from './components/Home.vue'
import Layout from './components/Layout.vue'

export default {
  extends: DefaultTheme,
  Layout,
  enhanceApp({ app }) {
    app.component('Home', Home)
  },
}
