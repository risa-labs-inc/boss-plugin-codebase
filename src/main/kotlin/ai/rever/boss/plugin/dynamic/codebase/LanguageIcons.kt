package ai.rever.boss.plugin.dynamic.codebase

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.SimpleIcons
import compose.icons.simpleicons.*

/**
 * Language/technology icons using official brand icons from Simple Icons.
 *
 * This provides a single source of truth for language icons and their official brand colors
 * for the dynamic Codebase plugin file tree.
 */
object LanguageIcons {

    // ═══════════════════════════════════════════════════════════════════════════
    // PROGRAMMING LANGUAGES
    // ═══════════════════════════════════════════════════════════════════════════

    val kotlin: ImageVector = SimpleIcons.Kotlin
    val java: ImageVector = SimpleIcons.Java
    val python: ImageVector = SimpleIcons.Python
    val javascript: ImageVector = SimpleIcons.Javascript
    val typescript: ImageVector = SimpleIcons.Typescript
    val go: ImageVector = SimpleIcons.Go
    val rust: ImageVector = SimpleIcons.Rust
    val swift: ImageVector = SimpleIcons.Swift
    val cpp: ImageVector = SimpleIcons.Cplusplus
    val c: ImageVector = SimpleIcons.C
    val csharp: ImageVector = SimpleIcons.Csharp
    val ruby: ImageVector = SimpleIcons.Ruby
    val php: ImageVector = SimpleIcons.Php
    val scala: ImageVector = SimpleIcons.Scala
    val haskell: ImageVector = SimpleIcons.Haskell
    val lua: ImageVector = SimpleIcons.Lua
    val perl: ImageVector = SimpleIcons.Perl
    val r: ImageVector = SimpleIcons.R
    val dart: ImageVector = SimpleIcons.Dart
    val elixir: ImageVector = SimpleIcons.Elixir
    val clojure: ImageVector = SimpleIcons.Clojure
    val julia: ImageVector = SimpleIcons.Julia
    val ocaml: ImageVector = SimpleIcons.Ocaml
    val zig: ImageVector = Icons.Outlined.Code
    val objectivec: ImageVector = Icons.Outlined.Code
    val fsharp: ImageVector = Icons.Outlined.Code
    val erlang: ImageVector = SimpleIcons.Erlang
    val nim: ImageVector = SimpleIcons.Nim
    val crystal: ImageVector = SimpleIcons.Crystal
    val fortran: ImageVector = SimpleIcons.Fortran
    val cobol: ImageVector = Icons.Outlined.Code
    val assembly: ImageVector = SimpleIcons.Assemblyscript
    val solidity: ImageVector = SimpleIcons.Solidity
    val vlang: ImageVector = SimpleIcons.V
    val dlang: ImageVector = Icons.Outlined.Code
    val groovy: ImageVector = SimpleIcons.Apachegroovy
    val rescript: ImageVector = Icons.Outlined.Code
    val racket: ImageVector = Icons.Outlined.Code

    // ═══════════════════════════════════════════════════════════════════════════
    // WEB FRAMEWORKS & RUNTIMES
    // ═══════════════════════════════════════════════════════════════════════════

    val react: ImageVector = SimpleIcons.React
    val vue: ImageVector = SimpleIcons.VueDotJs
    val angular: ImageVector = SimpleIcons.Angular
    val svelte: ImageVector = SimpleIcons.Svelte
    val astro: ImageVector = Icons.Outlined.Code

    // ═══════════════════════════════════════════════════════════════════════════
    // CSS PREPROCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    val sass: ImageVector = SimpleIcons.Sass
    val less: ImageVector = SimpleIcons.Less
    val stylus: ImageVector = SimpleIcons.Stylus

    // ═══════════════════════════════════════════════════════════════════════════
    // DATABASES
    // ═══════════════════════════════════════════════════════════════════════════

    val postgresql: ImageVector = SimpleIcons.Postgresql
    val sqlite: ImageVector = SimpleIcons.Sqlite
    val prisma: ImageVector = SimpleIcons.Prisma

    // ═══════════════════════════════════════════════════════════════════════════
    // DEVOPS & BUILD TOOLS
    // ═══════════════════════════════════════════════════════════════════════════

    val docker: ImageVector = SimpleIcons.Docker
    val kubernetes: ImageVector = SimpleIcons.Kubernetes
    val git: ImageVector = SimpleIcons.Git
    val github: ImageVector = SimpleIcons.Github
    val gradle: ImageVector = SimpleIcons.Gradle
    val maven: ImageVector = SimpleIcons.Apachemaven
    val npm: ImageVector = SimpleIcons.Npm
    val yarn: ImageVector = SimpleIcons.Yarn
    val cargo: ImageVector = SimpleIcons.Rust
    val pip: ImageVector = SimpleIcons.Pypi

    // ═══════════════════════════════════════════════════════════════════════════
    // LINTERS & FORMATTERS
    // ═══════════════════════════════════════════════════════════════════════════

    val eslint: ImageVector = SimpleIcons.Eslint
    val prettier: ImageVector = SimpleIcons.Prettier

    // ═══════════════════════════════════════════════════════════════════════════
    // API & DATA
    // ═══════════════════════════════════════════════════════════════════════════

    val graphql: ImageVector = SimpleIcons.Graphql
    val protobuf: ImageVector = Icons.Outlined.Code

    // ═══════════════════════════════════════════════════════════════════════════
    // MOBILE
    // ═══════════════════════════════════════════════════════════════════════════

    val ios: ImageVector = SimpleIcons.Apple

    // ═══════════════════════════════════════════════════════════════════════════
    // SHELL
    // ═══════════════════════════════════════════════════════════════════════════

    val gnubash: ImageVector = SimpleIcons.Gnubash
    val powershell: ImageVector = SimpleIcons.Powershell

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA FORMATS
    // ═══════════════════════════════════════════════════════════════════════════

    val json: ImageVector = SimpleIcons.Json
    val yaml: ImageVector = Icons.Outlined.Settings
    val toml: ImageVector = Icons.Outlined.Settings
    val markdown: ImageVector = SimpleIcons.Markdown

    // ═══════════════════════════════════════════════════════════════════════════
    // WEB
    // ═══════════════════════════════════════════════════════════════════════════

    val html: ImageVector = SimpleIcons.Html5
    val css: ImageVector = SimpleIcons.Css3

    // Fallback for unknown languages
    val unknown: ImageVector = Icons.Outlined.Code

    // ═══════════════════════════════════════════════════════════════════════════
    // OFFICIAL BRAND COLORS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Official brand colors for each language/technology.
     */
    object Colors {
        // Programming Languages
        val kotlin = Color(0xFF7F52FF)
        val java = Color(0xFFE76F00)
        val python = Color(0xFF3776AB)
        val javascript = Color(0xFFF7DF1E)
        val typescript = Color(0xFF3178C6)
        val go = Color(0xFF00ADD8)
        val rust = Color(0xFFDEA584)
        val swift = Color(0xFFF05138)
        val cpp = Color(0xFF00599C)
        val c = Color(0xFFA8B9CC)
        val csharp = Color(0xFF512BD4)
        val ruby = Color(0xFFCC342D)
        val php = Color(0xFF777BB4)
        val scala = Color(0xFFDC322F)
        val haskell = Color(0xFF5D4F85)
        val lua = Color(0xFF5C5CFF)
        val perl = Color(0xFF6B7EB8)
        val r = Color(0xFF276DC3)
        val dart = Color(0xFF0175C2)
        val elixir = Color(0xFF9B59B6)
        val clojure = Color(0xFF5881D8)
        val julia = Color(0xFF9558B2)
        val ocaml = Color(0xFFEC6813)
        val zig = Color(0xFFF7A41D)
        val objectivec = Color(0xFF438EFF)
        val fsharp = Color(0xFF378BBA)
        val erlang = Color(0xFFA90533)
        val nim = Color(0xFFFFE953)
        val crystal = Color(0xFFFFFFFF)
        val fortran = Color(0xFF734F96)
        val cobol = Color(0xFF005CA5)
        val assembly = Color(0xFF007AAC)
        val solidity = Color(0xFF8C8C8C)
        val vlang = Color(0xFF5D87BF)
        val dlang = Color(0xFFB03931)
        val groovy = Color(0xFF4298B8)
        val rescript = Color(0xFFE6484F)
        val racket = Color(0xFF9F1D20)

        // Web Frameworks
        val react = Color(0xFF61DAFB)
        val vue = Color(0xFF4FC08D)
        val angular = Color(0xFFDD0031)
        val svelte = Color(0xFFFF3E00)
        val astro = Color(0xFFFF5D01)

        // CSS
        val sass = Color(0xFFCC6699)
        val less = Color(0xFF1D365D)
        val stylus = Color(0xFF8C8C8C)

        // Databases
        val postgresql = Color(0xFF4169E1)
        val sqlite = Color(0xFF0F80CC)
        val prisma = Color(0xFF2D3748)

        // DevOps & Build Tools
        val docker = Color(0xFF2496ED)
        val kubernetes = Color(0xFF326CE5)
        val git = Color(0xFFF05032)
        val github = Color(0xFFFFFFFF)
        val gradle = Color(0xFF02ACC1)
        val maven = Color(0xFFC71A36)
        val npm = Color(0xFFCB3837)
        val yarn = Color(0xFF2C8EBB)
        val pnpm = Color(0xFFF69220)

        // Linters
        val eslint = Color(0xFF4B32C3)
        val prettier = Color(0xFFF7B93E)

        // API & Data
        val graphql = Color(0xFFE10098)
        val protobuf = Color(0xFF4285F4)

        // Mobile
        val ios = Color(0xFFFFFFFF)

        // Shell
        val gnubash = Color(0xFF4EAA25)
        val powershell = Color(0xFF5391FE)

        // Data formats
        val json = Color(0xFFCBCB41)
        val yaml = Color(0xFFCB171E)
        val toml = Color(0xFF9C4121)
        val markdown = Color(0xFFFFFFFF)

        // Web
        val html = Color(0xFFE34F26)
        val css = Color(0xFF1572B6)

        // Fallback
        val unknown = Color(0xFF808080)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOOKUP FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get icon and color for a file extension.
     * @param extension File extension without the dot (e.g., "kt", "py", "js")
     * @return Pair of ImageVector icon and Color
     */
    fun forExtension(extension: String): Pair<ImageVector, Color> {
        return when (extension.lowercase()) {
            // Kotlin
            "kt", "kts" -> kotlin to Colors.kotlin

            // Java
            "java" -> java to Colors.java

            // Python
            "py", "pyw", "pyx", "pxd", "pxi" -> python to Colors.python

            // JavaScript
            "js", "mjs", "cjs" -> javascript to Colors.javascript

            // TypeScript
            "ts", "mts", "cts" -> typescript to Colors.typescript
            "tsx" -> react to Colors.react
            "jsx" -> react to Colors.react

            // Go
            "go" -> go to Colors.go

            // Rust
            "rs" -> rust to Colors.rust

            // Swift
            "swift" -> swift to Colors.swift

            // Objective-C
            "m", "mm" -> objectivec to Colors.objectivec

            // C/C++
            "c", "h" -> c to Colors.c
            "cpp", "cc", "cxx", "hpp", "hxx", "hh" -> cpp to Colors.cpp

            // C#
            "cs" -> csharp to Colors.csharp

            // F#
            "fs", "fsx", "fsi" -> fsharp to Colors.fsharp

            // Ruby
            "rb", "erb", "rake" -> ruby to Colors.ruby

            // PHP
            "php" -> php to Colors.php

            // Scala
            "scala", "sc" -> scala to Colors.scala

            // Haskell
            "hs", "lhs" -> haskell to Colors.haskell

            // Lua
            "lua" -> lua to Colors.lua

            // Perl
            "pl", "pm" -> perl to Colors.perl

            // R
            "r", "rmd" -> r to Colors.r

            // Dart
            "dart" -> dart to Colors.dart

            // Elixir
            "ex", "exs" -> elixir to Colors.elixir

            // Erlang
            "erl", "hrl" -> erlang to Colors.erlang

            // Clojure
            "clj", "cljs", "cljc", "edn" -> clojure to Colors.clojure

            // Julia
            "jl" -> julia to Colors.julia

            // OCaml
            "ml", "mli" -> ocaml to Colors.ocaml

            // Zig
            "zig" -> zig to Colors.zig

            // Nim
            "nim", "nims" -> nim to Colors.nim

            // Crystal
            "cr" -> crystal to Colors.crystal

            // Fortran
            "f", "f90", "f95", "f03", "f08", "for" -> fortran to Colors.fortran

            // COBOL
            "cob", "cbl" -> cobol to Colors.cobol

            // Assembly
            "asm", "s" -> assembly to Colors.assembly

            // Solidity
            "sol" -> solidity to Colors.solidity

            // V
            "v" -> vlang to Colors.vlang

            // D
            "d" -> dlang to Colors.dlang

            // Groovy
            "groovy", "gvy", "gy", "gsh" -> groovy to Colors.groovy

            // ReScript
            "res", "resi" -> rescript to Colors.rescript

            // Racket
            "rkt" -> racket to Colors.racket

            // HTML
            "html", "htm", "xhtml" -> html to Colors.html

            // CSS
            "css" -> css to Colors.css
            "scss", "sass" -> sass to Colors.sass
            "less" -> less to Colors.less
            "styl" -> stylus to Colors.stylus

            // Vue
            "vue" -> vue to Colors.vue

            // Svelte
            "svelte" -> svelte to Colors.svelte

            // Astro
            "astro" -> astro to Colors.astro

            // Data formats
            "json" -> json to Colors.json
            "yaml", "yml" -> yaml to Colors.yaml
            "toml" -> toml to Colors.toml
            "md", "markdown" -> markdown to Colors.markdown

            // GraphQL
            "graphql", "gql" -> graphql to Colors.graphql

            // Protocol Buffers
            "proto" -> protobuf to Colors.protobuf

            // Prisma
            "prisma" -> prisma to Colors.prisma

            // Config files
            "gradle" -> gradle to Colors.gradle
            "xml", "pom" -> maven to Colors.maven

            // Shell
            "sh", "bash", "zsh" -> gnubash to Colors.gnubash
            "ps1", "psm1", "psd1" -> powershell to Colors.powershell

            // Docker
            "dockerfile" -> docker to Colors.docker

            // Git
            "gitignore", "gitattributes", "gitmodules" -> git to Colors.git

            // Database
            "sql" -> postgresql to Colors.postgresql

            // Linters/Formatters
            "eslintrc", "eslintignore" -> eslint to Colors.eslint
            "prettierrc", "prettierignore" -> prettier to Colors.prettier

            else -> unknown to Colors.unknown
        }
    }
}
