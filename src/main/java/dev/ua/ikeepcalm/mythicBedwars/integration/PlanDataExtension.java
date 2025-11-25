package dev.ua.ikeepcalm.mythicBedwars.integration;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.StringProvider;
import com.djrapitops.plan.extension.annotation.TableProvider;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import com.djrapitops.plan.extension.icon.Icon;
import com.djrapitops.plan.extension.table.Table;
import dev.ua.ikeepcalm.mythicBedwars.domain.core.StatisticsManager;
import dev.ua.ikeepcalm.mythicBedwars.domain.stats.db.PathwayStats;

import java.util.Map;

@PluginInfo(name = "MythicBedwars", iconName = "magic", iconFamily = Family.SOLID, color = Color.PURPLE)
public class PlanDataExtension implements DataExtension {

    private final StatisticsManager statisticsManager;

    public PlanDataExtension(StatisticsManager statisticsManager) {
        this.statisticsManager = statisticsManager;
    }

    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[]{
                CallEvents.SERVER_PERIODICAL,
                CallEvents.SERVER_EXTENSION_REGISTER
        };
    }

    @TableProvider(tableColor = Color.PURPLE)
    public Table pathwayPerformance() {
        Table.Factory table = Table.builder()
                .columnOne("Pathway", Icon.called("magic").build())
                .columnTwo("Wins", Icon.called("trophy").build())
                .columnThree("Losses", Icon.called("times").build())
                .columnFour("Win Rate", Icon.called("percentage").build())
                .columnFive("Avg Sequence", Icon.called("chart-line").build());

        for (Map.Entry<String, PathwayStats> entry : statisticsManager.getPathwayStatistics().entrySet()) {
            String pathway = entry.getKey();
            PathwayStats stats = entry.getValue();

            double winRate = stats.totalGames > 0 ? (double) stats.wins / stats.totalGames * 100 : 0;
            double avgSequence = stats.getAverageSequence();

            table.addRow(
                    pathway,
                    stats.wins,
                    stats.losses,
                    String.format("%.1f%%", winRate),
                    String.format("%.1f", avgSequence)
            );
        }

        return table.build();
    }

    @TableProvider(tableColor = Color.AMBER)
    public Table pathwayDamageStats() {
        Table.Factory table = Table.builder()
                .columnOne("Pathway", Icon.called("magic").build())
                .columnTwo("Total Damage", Icon.called("fire").build())
                .columnThree("Avg Damage/Game", Icon.called("chart-bar").build())
                .columnFour("Most Used Ability", Icon.called("star").build());

        for (Map.Entry<String, PathwayStats> entry : statisticsManager.getPathwayStatistics().entrySet()) {
            String pathway = entry.getKey();
            PathwayStats stats = entry.getValue();

            double avgDamage = stats.totalGames > 0 ? stats.totalDamageDealt / stats.totalGames : 0;
            String mostUsedAbility = stats.getMostUsedAbility();

            table.addRow(
                    pathway,
                    String.format("%.0f", stats.totalDamageDealt),
                    String.format("%.0f", avgDamage),
                    mostUsedAbility
            );
        }

        return table.build();
    }

    @NumberProvider(
            text = "Total Games Played",
            description = "Total number of unique MythicBedwars games played",
            priority = 100,
            iconName = "gamepad",
            iconColor = Color.PURPLE
    )
    public long totalGames() {
        return statisticsManager.totalGames();
    }

    @StringProvider(
            text = "Most Winning Pathway",
            description = "Pathway with the highest win rate",
            priority = 90,
            iconName = "crown",
            iconColor = Color.INDIGO
    )
    public String bestPathway() {
        return statisticsManager.bestPathway();
    }

    @NumberProvider(
            text = "Highest Win Rate",
            description = "The highest win rate percentage among all pathways",
            priority = 85,
            iconName = "percentage",
            iconColor = Color.GREEN,
            format = FormatType.NONE
    )
    public long highestWinRate() {
        return statisticsManager.highestWinRate();
    }

    @NumberProvider(
            text = "Average Game Duration",
            description = "Average duration of MythicBedwars games in minutes",
            priority = 80,
            iconName = "clock",
            iconColor = Color.LIGHT_BLUE,
            format = FormatType.NONE
    )
    public long averageGameDuration() {
        return statisticsManager.averageGameDuration();
    }

    @StringProvider(
            text = "Most Powerful Pathway",
            description = "Pathway that deals the most damage on average",
            priority = 75,
            iconName = "fire",
            iconColor = Color.RED
    )
    public String mostPowerfulPathway() {
        return statisticsManager.mostPowerfulPathway();
    }
}
