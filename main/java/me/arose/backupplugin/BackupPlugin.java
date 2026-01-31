package me.arose.backupplugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupPlugin extends JavaPlugin {

    private long intervalTicks;
    private long warningTicks;
    private String backupPath;
    private String webhook;

    private int maxBackups;
    private int maxAgeDays;

    private final Map<String, Boolean> worlds = new HashMap<>();

    /* ========================= ENABLE ========================= */

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadEverything();
        startScheduler();
        getLogger().info("BackupPlugin enabled");
        sendDiscordEmbed("Backup Plugin Enabled", "The backup system is now running.");
    }

    /* ========================= CONFIG ========================= */

    private void reloadEverything() {
        reloadConfig();

        intervalTicks = getConfig().getLong("backup.interval-seconds") * 20L;
        warningTicks  = getConfig().getLong("backup.warning-seconds") * 20L;
        backupPath    = getConfig().getString("backup.path");

        maxBackups = getConfig().getInt("cleanup.max-backups");
        maxAgeDays = getConfig().getInt("cleanup.max-age-days");

        webhook = getConfig().getString("discord.webhook-url");

        worlds.clear();
        if (getConfig().isConfigurationSection("worlds")) {
            for (String w : getConfig().getConfigurationSection("worlds").getKeys(false)) {
                worlds.put(w, getConfig().getBoolean("worlds." + w + ".enabled"));
            }
        }
    }

    /* ========================= SCHEDULER ========================= */

    private void startScheduler() {
        Bukkit.getScheduler().runTaskTimer(
                this,
                this::warnAndBackup,
                intervalTicks,
                intervalTicks
        );
    }

    /* ========================= WARNING ========================= */

    private void warnAndBackup() {
        Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                "tellraw @a {\"text\":\"[BackupPlugin] World backup will start in 3 seconds.\",\"color\":\"yellow\"}"
        );

        Bukkit.getScheduler().runTaskLater(this, this::runBackupAsync, warningTicks);
    }

    /* ========================= BACKUP ========================= */

    private void runBackupAsync() {
        for (World w : Bukkit.getWorlds()) {
            w.setAutoSave(false);
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Path zip = createZipBackup();

                // switch back to main thread AFTER success
                Bukkit.getScheduler().runTask(this, () -> onBackupSuccess(zip));

            } catch (Exception e) {
                getLogger().severe("Backup FAILED");
                e.printStackTrace();

                Bukkit.getScheduler().runTask(this, this::reenableAutosave);
            }
        });
    }

    private void reenableAutosave() {
        for (World w : Bukkit.getWorlds()) {
            w.setAutoSave(true);
        }
    }

    private Path createZipBackup() throws IOException {
        Files.createDirectories(Paths.get(backupPath));

        String timestamp =
                new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());

        Path zipFile =
                Paths.get(backupPath, "backup_" + timestamp + ".zip");

        try (ZipOutputStream zos =
                     new ZipOutputStream(Files.newOutputStream(zipFile))) {

            for (Map.Entry<String, Boolean> entry : worlds.entrySet()) {
                if (!entry.getValue()) continue;

                Path worldDir =
                        Bukkit.getWorldContainer().toPath().resolve(entry.getKey());

                if (!Files.exists(worldDir)) continue;

                zipFolder(worldDir, entry.getKey(), zos);
            }
        }

        return zipFile;
    }

    private void zipFolder(Path source, String root, ZipOutputStream zos)
            throws IOException {

        Files.walk(source)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        zos.putNextEntry(new ZipEntry(
                                root + "/" + source.relativize(path)
                                        .toString().replace("\\", "/")
                        ));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /* ========================= POST BACKUP ========================= */

    private void onBackupSuccess(Path zipFile) {
        reenableAutosave();

        String name = zipFile.getFileName().toString();

        getLogger().info("Backup completed: " + name);

        Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                "tellraw @a {\"text\":\"[BackupPlugin] Backup completed successfully!\",\"color\":\"green\"}"
        );

        sendDiscordEmbed(
                "Backup Completed",
                "Backup file created:\n`" + name + "`"
        );

        try {
            cleanupOldBackups(); // only AFTER success
        } catch (IOException e) {
            getLogger().warning("Cleanup failed");
            e.printStackTrace();
        }
    }

    /* ========================= CLEANUP ========================= */

    private void cleanupOldBackups() throws IOException {
        if (maxBackups <= 0 && maxAgeDays <= 0) return;

        Path dir = Paths.get(backupPath);
        if (!Files.exists(dir)) return;

        List<Path> backups;
        try (var stream = Files.list(dir)) {
            backups = stream
                    .filter(p -> p.toString().endsWith(".zip"))
                    .sorted((a, b) ->
                            Long.compare(b.toFile().lastModified(),
                                    a.toFile().lastModified()))
                    .collect(Collectors.toList());
        }

        if (maxBackups > 0 && backups.size() > maxBackups) {
            for (int i = maxBackups; i < backups.size(); i++) {
                backups.get(i).toFile().delete();
            }
        }

        if (maxAgeDays > 0) {
            long cutoff =
                    System.currentTimeMillis() - (maxAgeDays * 86400000L);

            for (Path p : backups) {
                if (p.toFile().lastModified() < cutoff) {
                    p.toFile().delete();
                }
            }
        }
    }

    /* ========================= DISCORD ========================= */

    private void sendDiscordEmbed(String title, String description) {
        if (!getConfig().getBoolean("discord.enabled")) return;

        try {
            HttpURLConnection con =
                    (HttpURLConnection) new URL(webhook).openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            String json = """
                    {
                      "embeds": [{
                        "title": "%s",
                        "description": "%s",
                        "color": 3066993
                      }]
                    }
                    """.formatted(title, description);

            con.getOutputStream()
                    .write(json.getBytes(StandardCharsets.UTF_8));
            con.getOutputStream().close();
            con.getInputStream().close();

        } catch (Exception ignored) {}
    }

    /* ========================= COMMANDS ========================= */

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {

        if (!sender.hasPermission("backup.admin")) return true;

        if (args.length == 1 && args[0].equalsIgnoreCase("now")) {
            warnAndBackup();
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadEverything();
            sender.sendMessage("§aBackupPlugin configuration reloaded.");
            return true;
        }
        return false;
    }
}
