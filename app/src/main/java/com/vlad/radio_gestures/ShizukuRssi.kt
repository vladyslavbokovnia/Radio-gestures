package com.vlad.radio_gestures

import rikka.shizuku.Shizuku

/**
 * Тонкая обёртка над Shizuku: проверка доступности, запрос разрешения
 * и выполнение shell-команды в привилегированном процессе через рефлексию
 * (Shizuku.newProcess официально скрыт из публичного API, но всё ещё
 * работает в текущей версии библиотеки — см. RikkaApps/Shizuku-API#276).
 */
object ShizukuRssi {

    const val REQUEST_CODE = 7001

    /** Бинарник Shizuku получен (сервис запущен и подключение установлено). */
    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** Разрешение приложению уже выдано (не спрашивая заново). */
    fun hasPermission(): Boolean = try {
        isBinderAlive() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (isBinderAlive() && !hasPermission()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Выполняет команду в shell-процессе Shizuku и возвращает (stdout, stderr).
     * Бросает исключение, если бинарник не подключён или нет разрешения —
     * вызывающий код должен сам проверить hasPermission() заранее.
     */
    fun exec(command: Array<String>): Pair<String, String> {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, command, null, null)
            ?: throw IllegalStateException("Shizuku.newProcess вернул null")

        val inputStream = process.javaClass.getMethod("getInputStream").invoke(process) as java.io.InputStream
        val errorStream = process.javaClass.getMethod("getErrorStream").invoke(process) as java.io.InputStream
        val out = inputStream.bufferedReader().use { it.readText() }
        val err = errorStream.bufferedReader().use { it.readText() }
        try {
            process.javaClass.getMethod("destroy").invoke(process)
        } catch (_: Throwable) {
        }
        return out to err
    }

    /**
     * Ищет в тексте dumpsys RSSI, относящийся к конкретному MAC-адресу.
     * Формат вывода отличается на разных прошивках/чипах, поэтому ищем
     * адрес и берём ближайшее упоминание "rssi: <число>" в пределах
     * следующих ~400 символов после адреса.
     */
    fun extractRssiForAddress(dumpsysOutput: String, address: String): Int? {
        val addrIndex = dumpsysOutput.indexOf(address, ignoreCase = true)
        if (addrIndex == -1) return null
        val window = dumpsysOutput.substring(
            addrIndex,
            minOf(dumpsysOutput.length, addrIndex + 400)
        )
        val match = Regex("rssi[^0-9-]{0,10}(-?\\d{1,3})", RegexOption.IGNORE_CASE).find(window)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}
