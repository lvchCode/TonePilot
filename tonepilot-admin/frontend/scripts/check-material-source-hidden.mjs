import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const forbidden = ['素材来源', '保存来源', '来源列表', '当前来源', '当前来源素材', '导入到当前来源', '请先选择或创建一个素材来源', '链接 + 字幕摘要', '手工素材', '导入手工素材']
const found = forbidden.filter(text => app.includes(text))
if (found.length > 0) {
  console.error(`管理端不应暴露素材来源用户概念：${found.join('、')}`)
  process.exit(1)
}
if (!app.includes('调色素材导入')) {
  console.error('管理端需要保留调色素材导入入口')
  process.exit(1)
}

if (!app.includes('上传视频解析')) {
  console.error('管理端素材导入只保留上传视频解析入口')
  process.exit(1)
}
