package dev.dcheckcloud.lang;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class Messages {

    private final Map<String, String> bundle;
    private final Map<String, String> errors;

    public Messages(String language) {
        boolean ru = "ru".equalsIgnoreCase(language);
        this.bundle = ru ? ru() : en();
        this.errors = ru ? errorsRu() : errorsEn();
    }

    public String get(String key, Object... args) {
        String pattern = bundle.getOrDefault(key, key);
        if (args.length == 0) {
            return pattern;
        }
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (RuntimeException ex) {
            StringBuilder fallback = new StringBuilder(pattern);
            for (Object arg : args) {
                fallback.append(' ').append(arg);
            }
            return fallback.toString();
        }
    }

    public String state(String name) {
        return bundle.getOrDefault("state." + name, name);
    }

    public String reason(String reason) {
        return bundle.getOrDefault("reason." + reason, reason);
    }

    public String translateError(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : errors.entrySet()) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return raw;
    }

    private static Map<String, String> errorsEn() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("unknown key", "no such key on the server");
        m.put("expired", "the key has expired");
        m.put("revoked", "the key was revoked");
        m.put("missing key", "no key in the config");
        m.put("license key not set", "no key in the config");
        m.put("invalid license key", "the key did not pass");
        m.put("no model on the server", "no model on the server yet");
        m.put("cloud unreachable", "cloud is unreachable");
        m.put("connection refused", "cloud is unreachable");
        m.put("timed out", "cloud did not answer in time");
        m.put("http connect timed out", "cloud did not answer in time");
        m.put("bad sequence size", "the cloud rejected the request");
        return m;
    }

    private static Map<String, String> errorsRu() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("unknown key", "такого ключа на сервере нет");
        m.put("expired", "срок ключа вышел");
        m.put("revoked", "ключ отозван");
        m.put("missing key", "ключ не вписан в конфиг");
        m.put("license key not set", "ключ не вписан в конфиг");
        m.put("invalid license key", "ключ не подошёл");
        m.put("no model on the server", "модель на сервере ещё не загружена");
        m.put("cloud unreachable", "облако недоступно");
        m.put("connection refused", "облако недоступно");
        m.put("timed out", "облако не ответило вовремя");
        m.put("http connect timed out", "облако не ответило вовремя");
        m.put("bad sequence size", "облако отклонило запрос");
        return m;
    }

    private static Map<String, String> en() {
        Map<String, String> m = new HashMap<>();
        m.put("help.compact", "&7Subcommands: &e/dcheck status &8| &edebug &8| &ealerts &8| &erecord &8| &everify &8| &ereload");
        m.put("err.playerOnly", "&c[DCheck] Players only.");
        m.put("err.consoleAlerts", "&c[DCheck] Console gets alerts anyway.");
        m.put("err.playerNotOnline", "&c[DCheck] %s is not online.");
        m.put("err.noData", "&c[DCheck] Nothing on %s yet.");
        m.put("err.specifyPlayer", "&c[DCheck] Which player?");
        m.put("err.usageStatus", "&c/dcheck status <player>");
        m.put("msg.reloadOk", "&a[DCheck] Reloaded. Engine %s, window %d, interval %dt");
        m.put("msg.reloadFail", "&c[DCheck] Reload failed: %s");
        m.put("msg.alertsOn", "&7[DCheck] Alerts &aon");
        m.put("msg.alertsOff", "&7[DCheck] Alerts &coff");
        m.put("err.usageRecord", "&cUsage: /dcheck record <player> [cheat] | /dcheck record stop <player> | /dcheck record cancel <player> | /dcheck record list");
        m.put("err.alreadyRecording", "&c[DCheck] Already recording %s.");
        m.put("err.notRecording", "&c[DCheck] %s is not being recorded.");
        m.put("err.recordStartFail", "&c[DCheck] Cannot start recording: %s");
        m.put("msg.recordStart", "&a[DCheck] Recording &e%s &aas &bcheat&a -> %s");
        m.put("msg.recordUploading", "&7[DCheck] Finishing and uploading the session of &e%s&7...");
        m.put("msg.recordSavedCloud", "&a[DCheck] Uploaded %d rows to the cloud (%s)");
        m.put("msg.recordSavedPending", "&e[DCheck] Upload failed (%s). Saved locally: %s - auto-retry in background");
        m.put("msg.recordCancelled", "&e[DCheck] Recording of &f%s &ediscarded (%d rows, nothing uploaded).");
        m.put("msg.recordListEmpty", "&7[DCheck] No active recordings.");
        m.put("msg.recordListHeader", "&7[DCheck] Active recordings:");
        m.put("msg.debugOn", "&7[DCheck] Watching &e%s&7.");
        m.put("msg.debugOff", "&7[DCheck] Not watching &e%s &7anymore.");
        m.put("msg.debugAllOn", "&7[DCheck] Watching everyone.");
        m.put("msg.debugAllOff", "&7[DCheck] Not watching everyone anymore.");
        m.put("msg.statusTitle", "&7&lDCheck on &f%s");
        m.put("msg.yes", "YES");
        m.put("msg.no", "no");
        m.put("msg.licenseOk", "&aLicense &f%s&a - &f%s &aday(s) left");
        m.put("msg.licenseInactive", "&cLicense is not working: &7%s");
        m.put("msg.cloudSessions", "&7In the cloud: &e%s &7legit, &e%s &7cheat sessions");
        m.put("alert.broadcast", "&8[&cDCheck&8] &f%s &7- &cAI-Aim &8[&7p=%s buf=%s score=%s flags=%s&8]");
        m.put("feed.empty", "&7no average &8/ &7no history yet");
        m.put("st.probability", "probability");
        m.put("st.buffer", "buffer");
        m.put("st.score", "score");
        m.put("st.sequence", "sequence");
        m.put("st.inferences", "inferences");
        m.put("st.state", "state");
        m.put("st.combat", "combat");
        m.put("st.target", "target");
        m.put("st.sinceAttack", "ticks since attack");
        m.put("st.immune", "immune");
        m.put("st.engine", "engine");
        m.put("st.flags", "flags");
        m.put("state.IDLE", "CALM");
        m.put("state.OBSERVATION", "WATCHING");
        m.put("state.SUSPICIOUS", "SUSPICIOUS");
        m.put("state.FLAGGED", "FLAGGED");
        m.put("state.IMMUNE", "IMMUNE");
        m.put("state.COLLECTING", "COLLECTING");
        m.put("reason.join", "join");
        m.put("reason.teleport", "teleport");
        m.put("reason.respawn", "respawn");
        m.put("reason.knockback", "knockback");
        m.put("reason.velocity", "knockback");
        m.put("reason.world-change", "world change");
        m.put("reason.gamemode-change", "gamemode change");
        m.put("reason.sensitivity-change", "sensitivity change");
        m.put("reason.plugin-enable", "plugin restart");
        m.put("reason.reload", "reload");
        return m;
    }

    private static Map<String, String> ru() {
        Map<String, String> m = new HashMap<>();
        m.put("help.compact", "&7Подкоманды: &e/dcheck status &8| &edebug &8| &ealerts &8| &erecord &8| &everify &8| &ereload");
        m.put("err.playerOnly", "&c[DCheck] Только из игры.");
        m.put("err.consoleAlerts", "&c[DCheck] Консоль и так всё получает.");
        m.put("err.playerNotOnline", "&c[DCheck] %s не в сети.");
        m.put("err.noData", "&c[DCheck] По %s пока ничего нет.");
        m.put("err.specifyPlayer", "&c[DCheck] Какого игрока?");
        m.put("err.usageStatus", "&c/dcheck status <игрок>");
        m.put("msg.reloadOk", "&a[DCheck] Перезагрузил. Движок %s, окно %d, интервал %dт");
        m.put("msg.reloadFail", "&c[DCheck] Не перезагрузилось: %s");
        m.put("msg.alertsOn", "&7[DCheck] Оповещения &aвключены");
        m.put("msg.alertsOff", "&7[DCheck] Оповещения &cвыключены");
        m.put("err.usageRecord", "&cИспользование: /dcheck record <игрок> [cheat] | /dcheck record stop <игрок> | /dcheck record cancel <игрок> | /dcheck record list");
        m.put("err.alreadyRecording", "&c[DCheck] %s уже записывается.");
        m.put("err.notRecording", "&c[DCheck] %s не записывается.");
        m.put("err.recordStartFail", "&c[DCheck] Не удалось начать запись: %s");
        m.put("msg.recordStart", "&a[DCheck] Запись &e%s &aкак &bcheat&a -> %s");
        m.put("msg.recordUploading", "&7[DCheck] Завершаю и загружаю сессию &e%s&7...");
        m.put("msg.recordSavedCloud", "&a[DCheck] Загружено %d строк в облако (%s)");
        m.put("msg.recordSavedPending", "&e[DCheck] Загрузка не удалась (%s). Сохранено локально: %s - повтор автоматически");
        m.put("msg.recordListEmpty", "&7[DCheck] Нет активных записей.");
        m.put("msg.recordListHeader", "&7[DCheck] Активные записи:");
        m.put("msg.recordCancelled", "&e[DCheck] Запись &f%s &eотменена (%d строк, ничего не отправлено).");
        m.put("msg.debugOn", "&7[DCheck] Слежу за &e%s&7.");
        m.put("msg.debugOff", "&7[DCheck] Больше не слежу за &e%s&7.");
        m.put("msg.debugAllOn", "&7[DCheck] Слежу за всеми.");
        m.put("msg.debugAllOff", "&7[DCheck] Больше ни за кем не слежу.");
        m.put("msg.statusTitle", "&7&lDCheck по игроку &f%s");
        m.put("msg.yes", "ДА");
        m.put("msg.no", "нет");
        m.put("msg.licenseOk", "&aЛицензия &f%s&a - ещё &f%s &aдн.");
        m.put("msg.licenseInactive", "&cЛицензия не работает: &7%s");
        m.put("msg.cloudSessions", "&7В облаке: &e%s &7legit, &e%s &7cheat сессий");
        m.put("alert.broadcast", "&8[&cDCheck&8] &f%s &7- &cAI-Aim &8[&7p=%s buf=%s score=%s flags=%s&8]");
        m.put("feed.empty", "&7нет среднего &8/ &7истории ещё нет");
        m.put("st.probability", "вероятность");
        m.put("st.buffer", "буфер");
        m.put("st.score", "очки");
        m.put("st.sequence", "секвенция");
        m.put("st.inferences", "инференсов");
        m.put("st.state", "состояние");
        m.put("st.combat", "бой");
        m.put("st.target", "цель");
        m.put("st.sinceAttack", "тиков с атаки");
        m.put("st.immune", "иммунитет");
        m.put("st.engine", "движок");
        m.put("st.flags", "флаги");
        m.put("state.IDLE", "СПОКОЕН");
        m.put("state.OBSERVATION", "НАБЛЮДЕНИЕ");
        m.put("state.SUSPICIOUS", "ПОДОЗРИТЕЛЬНО");
        m.put("state.FLAGGED", "ФЛАГ");
        m.put("state.IMMUNE", "ИММУНИТЕТ");
        m.put("state.COLLECTING", "СБОР ДАННЫХ");
        m.put("reason.join", "вход на сервер");
        m.put("reason.teleport", "телепорт");
        m.put("reason.respawn", "респаун");
        m.put("reason.knockback", "нокбек");
        m.put("reason.velocity", "отброс");
        m.put("reason.world-change", "смена мира");
        m.put("reason.gamemode-change", "смена режима");
        m.put("reason.sensitivity-change", "смена сенсы");
        m.put("reason.plugin-enable", "рестарт плагина");
        m.put("reason.reload", "рилоад");
        return m;
    }
}
