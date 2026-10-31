import { createApp, h } from 'vue'
import { ElAlert, ElButton, ElConfigProvider, ElDialog, ElDrawer, ElEmpty, ElForm, ElFormItem, ElInput, ElInputNumber, ElLoading, ElOption, ElSelect, ElSwitch, ElTable, ElTableColumn, ElTabPane, ElTabs, ElTag } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'

// 只注册工作台用到的组件，避免把整套组件打入首屏脚本。
const app = createApp({ render: () => h(ElConfigProvider, { locale: zhCn }, () => h(App)) })
for (const component of [ElAlert, ElButton, ElDialog, ElDrawer, ElEmpty, ElForm, ElFormItem, ElInput, ElInputNumber, ElOption, ElSelect, ElSwitch, ElTable, ElTableColumn, ElTabPane, ElTabs, ElTag]) app.use(component)
app.directive('loading', ElLoading.directive)
app.mount('#app')
