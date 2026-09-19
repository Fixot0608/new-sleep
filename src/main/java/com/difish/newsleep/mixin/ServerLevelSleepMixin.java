package com.difish.newsleep.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelSleepMixin {

    @Shadow public abstract List<ServerPlayer> players();

    // Переменные для отслеживания состояния нашего мода
    private static boolean isSleepAccelerated = false;
    private static int vanillaPercentageBackup = 100;

    @Inject(method = "*(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void manageSleepAcceleration(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        List<ServerPlayer> serverPlayers = this.players();

        if (serverPlayers.isEmpty()) return;

        MinecraftServer server = level.getServer();

        // Считаем активных и спящих игроков для НАШЕЙ кастомной логики
        long sleepingCount = serverPlayers.stream().filter(LivingEntity::isSleeping).count();
        long totalPlayers = serverPlayers.size();

        // Проверяем условия разгона на основе сохраненного ванильного процента
        boolean conditionsMet = (sleepingCount * 100) / totalPlayers >= vanillaPercentageBackup && sleepingCount > 0;

        if (conditionsMet) {
            long timeOfDay = level.getDayTime() % 24000;

            // Если идет ночь (от 13000 до 23000 тиков) — плавно крутим мир на 500 TPS
            if (timeOfDay >= 13000 && timeOfDay < 23000) {
                if (!isSleepAccelerated) {
                    // 1. Бекапим текущее серверное правило, чтобы не сломать настройки админа
                    vanillaPercentageBackup = level.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);

                    // 2. ОБМАН ВАНИЛЛЫ: Выставляем требование в 101% спящих.
                    // Ванильный сервер думает, что условий для пропуска ночи НЕТ, и блокирует рывок времени!
                    level.getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(101, server);

                    // 3. Разгоняем мир до гиперскорости
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tick rate 500");
                    isSleepAccelerated = true;
                }
            }
            // Если плавное время докрутилось до утра (перевалили за 23000 тиков)
            else if (timeOfDay >= 23000 || timeOfDay < 13000) {
                if (isSleepAccelerated) {
                    // 1. Возвращаем стандартную скорость 20 TPS
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tick rate 20");

                    // 2. Возвращаем ванильное правило из бекапа назад
                    level.getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(vanillaPercentageBackup, server);
                    isSleepAccelerated = false;
                }

                // 3. Самостоятельно и чисто будим игроков утром
                for (ServerPlayer player : serverPlayers) {
                    if (player.isSleeping()) {
                        player.stopSleepInBed(false, true);

                        // ЛОГИКА ФАНТОМОВ: Обнуляем игрокам статистику времени без сна
                        player.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
                    }
                }
            }
        } else {
            // Если кто-то проснулся раньше времени — мгновенно возвращаем всё назад
            if (isSleepAccelerated) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tick rate 20");
                level.getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(vanillaPercentageBackup, server);
                isSleepAccelerated = false;
            }
        }
    }
}
