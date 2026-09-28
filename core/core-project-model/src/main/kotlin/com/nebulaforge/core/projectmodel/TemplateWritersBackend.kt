package com.nebulaforge.core.projectmodel

import java.io.File

/** Go 后端可选框架矩阵。 */
internal enum class GoFramework(val label: String) {
    GIN("gin"), ECHO("echo"), FIBER("fiber"), GIN_GORM("gin+gorm")
}

// --------------------------------------------------------------------------
// Go（Gin / Echo / Fiber / Gin+GORM）
// --------------------------------------------------------------------------

internal fun createGo(root: File, moduleName: String, framework: GoFramework) {
    val module = moduleName.lowercase().replace(Regex("[^a-z0-9_/.-]"), "")
    val deps = when (framework) {
        GoFramework.GIN -> "require github.com/gin-gonic/gin v1.10.0\n"
        GoFramework.ECHO -> "require github.com/labstack/echo/v4 v4.12.0\n"
        GoFramework.FIBER -> "require github.com/gofiber/fiber/v2 v2.52.5\n"
        GoFramework.GIN_GORM -> "require (\n\tgithub.com/gin-gonic/gin v1.10.0\n\tgorm.io/driver/sqlite v1.5.6\n\tgorm.io/gorm v1.25.12\n)\n"
    }
    writeTemplateFile(root, "go.mod", "module $module\n\ngo 1.21\n\n$deps")
    val code = when (framework) {
        GoFramework.GIN -> GO_GIN
        GoFramework.ECHO -> GO_ECHO
        GoFramework.FIBER -> GO_FIBER
        GoFramework.GIN_GORM -> GO_GIN_GORM
    }
    writeTemplateFile(root, "main.go", code)
    writeTemplateFile(root, "README.md", "# $moduleName\n\n由星弦 IDE 生成。运行：\n\n```bash\ngo mod tidy\ngo run .\n```\n\n服务默认监听 `:8080`，健康检查：`GET /ping`。\n")
}

private const val GO_GIN = """package main

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

func main() {
	r := gin.Default()
	r.GET("/ping", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"message": "pong"})
	})
	r.Run(":8080")
}
"""

private const val GO_ECHO = """package main

import (
	"net/http"

	"github.com/labstack/echo/v4"
)

func main() {
	e := echo.New()
	e.GET("/ping", func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]string{"message": "pong"})
	})
	e.Logger.Fatal(e.Start(":8080"))
}
"""

private const val GO_FIBER = """package main

import "github.com/gofiber/fiber/v2"

func main() {
	app := fiber.New()
	app.Get("/ping", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"message": "pong"})
	})
	app.Listen(":8080")
}
"""

private const val GO_GIN_GORM = """package main

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

type Todo struct {
	ID    uint   `gorm:"primaryKey" json:"id"`
	Title string `json:"title"`
}

func main() {
	db, err := gorm.Open(sqlite.Open("app.db"), &gorm.Config{})
	if err != nil {
		panic(err)
	}
	if err := db.AutoMigrate(&Todo{}); err != nil {
		panic(err)
	}

	r := gin.Default()
	r.GET("/todos", func(c *gin.Context) {
		var todos []Todo
		db.Find(&todos)
		c.JSON(http.StatusOK, todos)
	})
	r.POST("/todos", func(c *gin.Context) {
		var todo Todo
		if err := c.ShouldBindJSON(&todo); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		db.Create(&todo)
		c.JSON(http.StatusCreated, todo)
	})
	r.Run(":8080")
}
"""

// --------------------------------------------------------------------------
// Node（Express / NestJS）
// --------------------------------------------------------------------------

internal fun createNodeExpress(root: File, moduleName: String) {
    val name = moduleName.lowercase().replace(Regex("[^a-z0-9_.-]"), "-")
    writeTemplateFile(root, "package.json", """{
  "name": "$name",
  "version": "1.0.0",
  "private": true,
  "main": "src/index.js",
  "scripts": {
    "start": "node src/index.js",
    "dev": "node --watch src/index.js",
    "build": "node --check src/index.js"
  },
  "dependencies": {
    "express": "^4.19.2"
  }
}
""")
    writeTemplateFile(root, "src/index.js", """const express = require('express');

const app = express();
app.use(express.json());

app.get('/ping', (req, res) => res.json({ message: 'pong' }));

const port = process.env.PORT || 3000;
app.listen(port, () => console.log('Server listening on http://localhost:' + port));
""")
    writeTemplateFile(root, "README.md", "# $moduleName\n\nExpress 服务端骨架。\n\n```bash\nnpm install\nnpm start\n```\n")
}

internal fun createNodeNest(root: File, moduleName: String) {
    val name = moduleName.lowercase().replace(Regex("[^a-z0-9_.-]"), "-")
    writeTemplateFile(root, "package.json", """{
  "name": "$name",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "start": "nest start",
    "start:dev": "nest start --watch",
    "build": "nest build"
  },
  "dependencies": {
    "@nestjs/common": "^10.4.4",
    "@nestjs/core": "^10.4.4",
    "@nestjs/platform-express": "^10.4.4",
    "reflect-metadata": "^0.2.2",
    "rxjs": "^7.8.1"
  },
  "devDependencies": {
    "@nestjs/cli": "^10.4.5",
    "@types/node": "^22.7.4",
    "ts-node": "^10.9.2",
    "typescript": "^5.6.2"
  }
}
""")
    writeTemplateFile(root, "nest-cli.json", """{
  "${'$'}schema": "https://json.schemastore.org/nest-cli",
  "collection": "@nestjs/schematics",
  "sourceRoot": "src"
}
""")
    writeTemplateFile(root, "tsconfig.json", """{
  "compilerOptions": {
    "module": "commonjs",
    "target": "ES2021",
    "experimentalDecorators": true,
    "emitDecoratorMetadata": true,
    "outDir": "dist",
    "sourceMap": true,
    "strict": true
  },
  "include": ["src"]
}
""")
    writeTemplateFile(root, "src/main.ts", """import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  await app.listen(3000);
  console.log('NestJS listening on http://localhost:3000');
}
bootstrap();
""")
    writeTemplateFile(root, "src/app.controller.ts", """import { Controller, Get } from '@nestjs/common';
import { AppService } from './app.service';

@Controller()
export class AppController {
  constructor(private readonly appService: AppService) {}

  @Get('ping')
  ping(): { message: string } {
    return this.appService.ping();
  }
}
""")
    writeTemplateFile(root, "src/app.service.ts", """import { Injectable } from '@nestjs/common';

@Injectable()
export class AppService {
  ping(): { message: string } {
    return { message: 'pong' };
  }
}
""")
    writeTemplateFile(root, "src/app.module.ts", """import { Module } from '@nestjs/common';
import { AppController } from './app.controller';
import { AppService } from './app.service';

@Module({
  controllers: [AppController],
  providers: [AppService],
})
export class AppModule {}
""")
}

// --------------------------------------------------------------------------
// Java（Spring Boot / Maven）
// --------------------------------------------------------------------------

internal fun createSpringBoot(root: File, moduleName: String, packageName: String) {
    val artifact = moduleName.lowercase().replace(Regex("[^a-z0-9-]"), "-").ifBlank { "nebulaforge-app" }
    writeTemplateFile(root, "pom.xml", """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.4</version>
        <relativePath/>
    </parent>

    <groupId>$packageName</groupId>
    <artifactId>$artifact</artifactId>
    <version>1.0.0</version>
    <name>$artifact</name>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
""")
    val pkgPath = packageName.replace('.', '/')
    writeTemplateFile(root, "src/main/java/$pkgPath/Application.java", """package $packageName;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }
}
""")
    writeTemplateFile(root, "src/main/resources/application.properties", "server.port=8080\n")
    writeTemplateFile(root, "README.md", "# $moduleName\n\nSpring Boot 服务骨架。\n\n```bash\nmvn spring-boot:run\n```\n")
}

// --------------------------------------------------------------------------
// Python（FastAPI / Django / Flask）
// --------------------------------------------------------------------------

internal fun createFastApi(root: File, moduleName: String) {
    writeTemplateFile(root, "requirements.txt", "fastapi>=0.115.0\nuvicorn[standard]>=0.31.0\n")
    writeTemplateFile(root, "main.py", """from fastapi import FastAPI

app = FastAPI(title="$moduleName")


@app.get("/ping")
def ping() -> dict[str, str]:
    return {"message": "pong"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
""")
    writeTemplateFile(root, "README.md", "# $moduleName\n\nFastAPI 服务骨架。\n\n```bash\npip install -r requirements.txt\nuvicorn main:app --reload --host 0.0.0.0 --port 8000\n```\n")
}

internal fun createFlask(root: File, moduleName: String) {
    writeTemplateFile(root, "requirements.txt", "Flask>=3.0.3\n")
    writeTemplateFile(root, "app.py", """from flask import Flask, jsonify

app = Flask(__name__)


@app.get("/ping")
def ping():
    return jsonify(message="pong")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
""")
    writeTemplateFile(root, "README.md", "# $moduleName\n\nFlask 服务骨架。\n\n```bash\npip install -r requirements.txt\npython app.py\n```\n")
}

internal fun createDjango(root: File, moduleName: String) {
    val proj = moduleName.lowercase().replace(Regex("[^a-z0-9_]"), "_").ifBlank { "config" }
    writeTemplateFile(root, "requirements.txt", "Django>=5.0.9\n")
    writeTemplateFile(root, "manage.py", """#!/usr/bin/env python
import os
import sys


def main() -> None:
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "$proj.settings")
    from django.core.management import execute_from_command_line

    execute_from_command_line(sys.argv)


if __name__ == "__main__":
    main()
""")
    writeTemplateFile(root, "$proj/__init__.py", "")
    writeTemplateFile(root, "$proj/settings.py", """from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

SECRET_KEY = "django-insecure-change-me"
DEBUG = True
ALLOWED_HOSTS = ["*"]

INSTALLED_APPS = [
    "django.contrib.contenttypes",
    "django.contrib.staticfiles",
]

MIDDLEWARE = [
    "django.middleware.common.CommonMiddleware",
]

ROOT_URLCONF = "$proj.urls"
WSGI_APPLICATION = "$proj.wsgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": BASE_DIR / "db.sqlite3",
    }
}

LANGUAGE_CODE = "zh-hans"
TIME_ZONE = "Asia/Shanghai"
USE_I18N = True
USE_TZ = True
STATIC_URL = "static/"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
""")
    writeTemplateFile(root, "$proj/urls.py", """from django.http import JsonResponse
from django.urls import path


def ping(_request):
    return JsonResponse({"message": "pong"})


urlpatterns = [
    path("ping", ping),
]
""")
    writeTemplateFile(root, "$proj/wsgi.py", """import os

from django.core.wsgi import get_wsgi_application

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "$proj.settings")

application = get_wsgi_application()
""")
    writeTemplateFile(root, "README.md", "# $moduleName\n\nDjango 项目骨架。\n\n```bash\npip install -r requirements.txt\npython manage.py migrate\npython manage.py runserver 0.0.0.0:8000\n```\n")
}

// --------------------------------------------------------------------------
// PHP（Laravel 骨架）
// --------------------------------------------------------------------------

internal fun createLaravel(root: File, moduleName: String) {
    val name = moduleName.lowercase().replace(Regex("[^a-z0-9/_-]"), "-")
    writeTemplateFile(root, "composer.json", """{
    "name": "nebulaforge/$name",
    "type": "project",
    "description": "Laravel 骨架，由星弦 IDE 生成",
    "require": {
        "php": "^8.2",
        "laravel/framework": "^11.0"
    },
    "autoload": {
        "psr-4": {
            "App\\": "app/",
            "Database\\Factories\\": "database/factories/",
            "Database\\Seeders\\": "database/seeders/"
        }
    },
    "scripts": {
        "serve": "php -S 0.0.0.0:8000 -t public"
    }
}
""")
    writeTemplateFile(root, "public/index.php", """<?php

use Illuminate\Http\Request;

define('LARAVEL_START', microtime(true));

// 骨架入口：引入 Composer 自动加载后交由框架处理请求。
require __DIR__.'/../vendor/autoload.php';

${'$'}app = require_once __DIR__.'/../bootstrap/app.php';

${'$'}app->handleRequest(Request::capture());
""")
    writeTemplateFile(root, "bootstrap/app.php", """<?php

use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        web: __DIR__.'/../routes/web.php',
        commands: __DIR__.'/../routes/console.php',
        health: '/up',
    )
    ->withMiddleware(function (Middleware ${'$'}middleware) {
        //
    })
    ->withExceptions(function (Exceptions ${'$'}exceptions) {
        //
    })->create();
""")
    writeTemplateFile(root, "routes/web.php", """<?php

use Illuminate\Support\Facades\Route;

Route::get('/', function () {
    return ['message' => 'pong'];
});
""")
    writeTemplateFile(root, "routes/console.php", """<?php

use Illuminate\Support\Facades\Artisan;

// 自定义 Artisan 命令可在此注册。
""")
    writeTemplateFile(root, "app/Http/Controllers/Controller.php", """<?php

namespace App\Http\Controllers;

abstract class Controller
{
    //
}
""")
    writeTemplateFile(root, ".env.example", """APP_NAME=Laravel
APP_ENV=local
APP_KEY=
APP_DEBUG=true
APP_URL=http://localhost

DB_CONNECTION=sqlite
""")
    writeTemplateFile(root, "README.md", "# $moduleName\n\nLaravel 目录骨架（不含 vendor）。\n\n```bash\ncomposer install\nphp artisan key:generate\ncomposer run serve\n```\n")
}

// --------------------------------------------------------------------------
// Rust（Axum）
// --------------------------------------------------------------------------

internal fun createRustAxum(root: File, moduleName: String) {
    val crate = moduleName.lowercase().replace(Regex("[^a-z0-9_]"), "_").ifBlank { "nebulaforge_app" }
    writeTemplateFile(root, "Cargo.toml", """[package]
name = "$crate"
version = "0.1.0"
edition = "2021"

[dependencies]
axum = "0.7"
tokio = { version = "1", features = ["full"] }
serde_json = "1"
""")
    writeTemplateFile(root, "src/main.rs", """use axum::{routing::get, Json, Router};
use serde_json::{json, Value};

#[tokio::main]
async fn main() {
    let app = Router::new().route("/ping", get(ping));
    let listener = tokio::net::TcpListener::bind("0.0.0.0:8080").await.unwrap();
    println!("listening on {}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}

async fn ping() -> Json<Value> {
    Json(json!({ "message": "pong" }))
}
""")
    writeTemplateFile(root, "README.md", "# $moduleName\n\nAxum 服务骨架。\n\n```bash\ncargo run\n```\n")
}
