package com.nebulaforge.core.projectmodel

import java.io.File

/** 前端模板共用的 npm 包名清洗。 */
private fun npmName(moduleName: String): String =
    moduleName.lowercase().replace(Regex("[^a-z0-9_.-]"), "-").ifBlank { "nebulaforge-web" }

// --------------------------------------------------------------------------
// Vue 3 + Vite
// --------------------------------------------------------------------------

internal fun createVue(root: File, moduleName: String) {
    val name = npmName(moduleName)
    writeTemplateFile(root, "package.json", """{
  "name": "$name",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "vue": "^3.5.12"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.1.4",
    "vite": "^5.4.9"
  }
}
""")
    writeTemplateFile(root, "vite.config.js", """import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: { host: true, port: 5173 },
});
""")
    writeTemplateFile(root, "index.html", """<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>$name</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.js"></script>
  </body>
</html>
""")
    writeTemplateFile(root, "src/main.js", """import { createApp } from 'vue';
import App from './App.vue';

createApp(App).mount('#app');
""")
    writeTemplateFile(root, "src/App.vue", """<template>
  <main>
    <h1>{{ title }}</h1>
    <p>由星弦 IDE 生成的 Vue 3 项目。</p>
  </main>
</template>

<script setup>
import { ref } from 'vue';

const title = ref('Hello Nebula Forge');
</script>

<style scoped>
main {
  font-family: system-ui, sans-serif;
  padding: 24px;
}
</style>
""")
}

// --------------------------------------------------------------------------
// React + Vite
// --------------------------------------------------------------------------

internal fun createReact(root: File, moduleName: String) {
    val name = npmName(moduleName)
    writeTemplateFile(root, "package.json", """{
  "name": "$name",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@vitejs/plugin-react": "^4.3.2",
    "vite": "^5.4.9"
  }
}
""")
    writeTemplateFile(root, "vite.config.js", """import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: { host: true, port: 5173 },
});
""")
    writeTemplateFile(root, "index.html", """<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>$name</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.jsx"></script>
  </body>
</html>
""")
    writeTemplateFile(root, "src/main.jsx", """import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';

createRoot(document.getElementById('root')).render(<App />);
""")
    writeTemplateFile(root, "src/App.jsx", """export default function App() {
  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: 24 }}>
      <h1>Hello Nebula Forge</h1>
      <p>由星弦 IDE 生成的 React 项目。</p>
    </main>
  );
}
""")
}

// --------------------------------------------------------------------------
// Next.js
// --------------------------------------------------------------------------

internal fun createNext(root: File, moduleName: String) {
    val name = npmName(moduleName)
    writeTemplateFile(root, "package.json", """{
  "name": "$name",
  "private": true,
  "version": "0.0.0",
  "scripts": {
    "dev": "next dev -H 0.0.0.0",
    "build": "next build",
    "start": "next start"
  },
  "dependencies": {
    "next": "^14.2.15",
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  }
}
""")
    writeTemplateFile(root, "next.config.js", """/** @type {import('next').NextConfig} */
const nextConfig = {};

module.exports = nextConfig;
""")
    writeTemplateFile(root, "jsconfig.json", """{
  "compilerOptions": {
    "baseUrl": ".",
    "paths": { "@/*": ["./*"] }
  }
}
""")
    writeTemplateFile(root, "app/layout.js", """export const metadata = {
  title: '$name',
  description: '由星弦 IDE 生成的 Next.js 项目',
};

export default function RootLayout({ children }) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
""")
    writeTemplateFile(root, "app/page.js", """export default function Home() {
  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: 24 }}>
      <h1>Hello Nebula Forge</h1>
      <p>由星弦 IDE 生成的 Next.js 项目。</p>
    </main>
  );
}
""")
    writeTemplateFile(root, ".gitignore", "node_modules\n.next\n")
}

// --------------------------------------------------------------------------
// Svelte + Vite
// --------------------------------------------------------------------------

internal fun createSvelte(root: File, moduleName: String) {
    val name = npmName(moduleName)
    writeTemplateFile(root, "package.json", """{
  "name": "$name",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "devDependencies": {
    "@sveltejs/vite-plugin-svelte": "^3.1.2",
    "svelte": "^4.2.19",
    "vite": "^5.4.9"
  }
}
""")
    writeTemplateFile(root, "svelte.config.js", """import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

export default {
  preprocess: vitePreprocess(),
};
""")
    writeTemplateFile(root, "vite.config.js", """import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

export default defineConfig({
  plugins: [svelte()],
  server: { host: true, port: 5173 },
});
""")
    writeTemplateFile(root, "index.html", """<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>$name</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.js"></script>
  </body>
</html>
""")
    writeTemplateFile(root, "src/main.js", """import App from './App.svelte';

const app = new App({
  target: document.getElementById('app'),
});

export default app;
""")
    writeTemplateFile(root, "src/App.svelte", """<script>
  let title = 'Hello Nebula Forge';
</script>

<main>
  <h1>{title}</h1>
  <p>由星弦 IDE 生成的 Svelte 项目。</p>
</main>
""")
}

// --------------------------------------------------------------------------
// Angular
// --------------------------------------------------------------------------

internal fun createAngular(root: File, moduleName: String) {
    val name = npmName(moduleName)
    writeTemplateFile(root, "package.json", """{
  "name": "$name",
  "version": "0.0.0",
  "private": true,
  "scripts": {
    "start": "ng serve --host 0.0.0.0",
    "build": "ng build"
  },
  "dependencies": {
    "@angular/common": "^18.2.0",
    "@angular/compiler": "^18.2.0",
    "@angular/core": "^18.2.0",
    "@angular/platform-browser": "^18.2.0",
    "@angular/platform-browser-dynamic": "^18.2.0",
    "@angular/router": "^18.2.0",
    "rxjs": "~7.8.1",
    "tslib": "^2.7.0",
    "zone.js": "~0.14.10"
  },
  "devDependencies": {
    "@angular-devkit/build-angular": "^18.2.9",
    "@angular/cli": "^18.2.9",
    "@angular/compiler-cli": "^18.2.9",
    "typescript": "~5.5.4"
  }
}
""")
    writeTemplateFile(root, "angular.json", """{
  "${'$'}schema": "./node_modules/@angular/cli/lib/config/schema.json",
  "version": 1,
  "newProjectRoot": "projects",
  "projects": {
    "$name": {
      "projectType": "application",
      "root": "",
      "sourceRoot": "src",
      "prefix": "app",
      "architect": {
        "build": {
          "builder": "@angular-devkit/build-angular:application",
          "options": {
            "outputPath": "dist/$name",
            "index": "src/index.html",
            "browser": "src/main.ts",
            "styles": ["src/styles.css"],
            "tsConfig": "tsconfig.json"
          }
        },
        "serve": {
          "builder": "@angular-devkit/build-angular:dev-server",
          "options": { "buildTarget": "$name:build" }
        }
      }
    }
  }
}
""")
    writeTemplateFile(root, "tsconfig.json", """{
  "compileOnSave": false,
  "compilerOptions": {
    "target": "ES2022",
    "module": "ES2022",
    "moduleResolution": "bundler",
    "experimentalDecorators": true,
    "strict": true,
    "skipLibCheck": true,
    "lib": ["ES2022", "dom"]
  },
  "angularCompilerOptions": {
    "strictTemplates": true
  }
}
""")
    writeTemplateFile(root, "src/index.html", """<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8" />
    <title>$name</title>
    <base href="/" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
  </head>
  <body>
    <app-root></app-root>
  </body>
</html>
""")
    writeTemplateFile(root, "src/styles.css", "body { font-family: system-ui, sans-serif; }\n")
    writeTemplateFile(root, "src/main.ts", """import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';

bootstrapApplication(AppComponent).catch((err) => console.error(err));
""")
    writeTemplateFile(root, "src/app/app.component.ts", """import { Component } from '@angular/core';

@Component({
  selector: 'app-root',
  standalone: true,
  template: `
    <main style="padding: 24px">
      <h1>{{ title }}</h1>
      <p>由星弦 IDE 生成的 Angular 项目。</p>
    </main>
  `,
})
export class AppComponent {
  title = 'Hello Nebula Forge';
}
""")
}
