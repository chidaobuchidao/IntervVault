# 面面通 Web 前端

基于 Vue 3、TypeScript、Vite 和 Pinia，提供 AI 面试、题库练习、简历优化和论文处理页面。完整项目介绍与后端启动步骤见 [项目 README](../../README.md)。

## 环境与启动

- Node.js 22.13.0+。版本下限来自当前 `package-lock.json` 中的 `pdfjs-dist`，Node.js 18 无法满足当前构建依赖的要求。
- npm，以及已启动的 Spring Boot 后端，默认地址为 `http://localhost:8080`。

从仓库根目录执行：

```bash
cd AI-Interview/web-app
npm ci
npm run dev
```

打开终端输出的本地地址，默认是 `http://localhost:5173`。`npm ci` 使用锁文件安装确定版本的依赖；有意调整依赖时，再使用 `npm install` 并一起提交 `package.json` 与 `package-lock.json`。

## 常用命令

以下命令均在 `AI-Interview/web-app` 目录执行：

| 命令 | 用途 |
| --- | --- |
| `npm run dev` | 启动带热更新的开发服务器 |
| `npx vue-tsc -b` | 只运行 TypeScript 与 Vue 类型检查 |
| `npm run build` | 先运行类型检查，再生成 `dist/` 生产资源 |
| `npm run preview` | 本地预览已生成的 `dist/`，需先完成构建 |

提交前至少运行 `npm run build`。当前未配置独立的前端单元测试脚本，构建通过后仍需检查涉及的页面交互。

## API 与部署

开发环境的 `vite.config.ts` 将 `/api` 请求转发到 `http://localhost:8080`。若后端监听其他地址，修改该文件的 `server.proxy['/api'].target`。

生产环境需要在反向代理中配置 `/api` 转发，并为 Vue Router 的历史模式提供回退到 `index.html` 的规则。Vite 的开发代理配置不会写入 `dist/`，本地预览也不能替代生产服务器配置。

论文 PDF 解析使用 `.mjs` worker，部署时需要正确返回 JavaScript MIME 类型。Nginx 配置示例见 [项目 README 的部署提示](../../README.md#部署提示)。

## 常见问题

- 安装出现 `EBADENGINE`：先运行 `node --version`，确认 Node.js 满足上述版本要求。
- `/api` 请求连接失败：确认后端已启动，且开发代理目标地址与后端监听地址一致。
- `npm ci` 提示锁文件不一致：确认 `package.json` 与 `package-lock.json` 来自同一提交；若刚修改依赖，先运行 `npm install` 更新锁文件并检查差异。
- 构建提示资源块超过 500 kB：这是资源体积警告。可根据实际加载耗时评估按需加载和拆包，构建是否成功以进程退出状态为准。
