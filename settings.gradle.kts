include(":core")

include(":lib-ratelimit")
project(":lib-ratelimit").projectDir = File("lib/ratelimit")

include(":lib-dataimage")
project(":lib-dataimage").projectDir = File("lib/dataimage")

if (System.getenv("CI") == null) {
    // Local development (full project build)

    include(":multisrc")
    project(":multisrc").projectDir = File("multisrc")

    // Loads all extensions
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            val name = ":extensions:individual:${dir.name}:${subdir.name}"
            include(name)
            project(name).projectDir = File("src/${dir.name}/${subdir.name}")
        }
    }
    // Loads generated extensions from multisrc
    File(rootDir, "generated-src").eachDir { dir ->
        dir.eachDir { subdir ->
            val name = ":extensions:multisrc:${dir.name}:${subdir.name}"
            include(name)
            project(name).projectDir = File("generated-src/${dir.name}/${subdir.name}")
        }
    }

    /**
     * If you're developing locally and only want to work with a single module,
     * comment out the parts above and uncomment below.
     */
//    val lang = "all"
//    val name = "mangadex"
//    val projectName = ":extensions:individual:$lang:$name"
//    val projectName = ":extensions:multisrc:$lang:$name"
//    include(projectName)
//    project(projectName).projectDir = File("src/${lang}/${name}")
//    project(projectName).projectDir = File("generated-src/${lang}/${name}")
} else {
    // Running in CI (GitHub Actions)

    val isMultisrc = System.getenv("CI_MULTISRC") == "true"
    val lang = System.getenv("CI_MATRIX_LANG")

    if (isMultisrc) {
        include(":multisrc")
        project(":multisrc").projectDir = File("multisrc")

        // Loads generated extensions from multisrc
        File(rootDir, "generated-src").eachDir { dir ->
            if (dir.name == lang) {
                dir.eachDir { subdir ->
                    val name = ":extensions:multisrc:${dir.name}:${subdir.name}"
                    include(name)
                    project(name).projectDir = File("generated-src/${dir.name}/${subdir.name}")
                }
            }
        }
    } else {
        // Loads all extensions
        File(rootDir, "src").eachDir { dir ->
            if (dir.name == lang) {
                dir.eachDir { subdir ->
                    val name = ":extensions:individual:${dir.name}:${subdir.name}"
                    include(name)
                    project(name).projectDir = File("src/${dir.name}/${subdir.name}")
                }
            }
        }
    }
}

inline fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
