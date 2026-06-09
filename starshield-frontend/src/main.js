import { createApp } from 'vue'
import {
  ElButton,
  ElDatePicker,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElLoading,
  ElOption,
  ElProgress,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElSlider,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTimeline,
  ElTimelineItem
} from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/tailwind.css'
import App from './App.vue'

const app = createApp(App)

const elementComponents = [
  ElButton,
  ElDatePicker,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElProgress,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElSlider,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTimeline,
  ElTimelineItem
]

for (const component of elementComponents) {
  app.use(component)
}
app.use(ElLoading)

app.mount('#app')
