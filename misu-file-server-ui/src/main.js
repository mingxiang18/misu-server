/*
  Import order matters:
  1. element-plus default styles first (sets baseline --el-* variables)
  2. our globals.css (tokens + reset + element-overrides) last,
     so our :root overrides win the cascade.
*/
import 'element-plus/dist/index.css'
import './styles/globals.css'

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import ElementPlus from 'element-plus'

createApp(App)
    .use(router)
    .use(ElementPlus)
    .mount('#app')
