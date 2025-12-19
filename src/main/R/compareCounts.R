#!/usr/bin/env Rscript

# traffic_analysis.R
# 0. Get the run directory from the command-line arguments
args <- commandArgs(trailingOnly = TRUE)
if (length(args) < 1) {
  stop("Usage: traffic_analysis.R <RUNDIR>")
}
run_dir <- args[1]

# 1. Read the Sollwerte (target) CSV
soll_file <- file.path("rvr_counts_sollwerte.csv")
if (!file.exists(soll_file)) {
  stop(paste("Sollwerte file not found:", soll_file))
}
soll <- read.csv(soll_file, stringsAsFactors = FALSE)

# 2. Read the simulation data
sim_file <- file.path(run_dir, "analysis", "traffic", "traffic_stats_by_link_daily.csv")
if (!file.exists(sim_file)) {
  stop(paste("Simulation file not found:", sim_file))
}
sim <- read.csv(sim_file, stringsAsFactors = FALSE)

# 3. Add a count_id column to the Sollwerte table
soll$count_id <- seq_len(nrow(soll))

# 4. Sum all truck volumes in the simulation table
#    (sum of vol_truck40t, vol_truck8t, vol_truck26t, vol_truck18t)
library(dplyr)
truck_cols <- grep("^vol_truck", names(sim), value = TRUE)
if (length(truck_cols) == 0) {
  stop("Keine Spalten mit 'vol_truck' in den Simulationsdaten gefunden.")
}
sim <- sim %>%
  mutate(
    truck_volume = rowSums(select(., all_of(truck_cols)), na.rm = TRUE)
  )

# 5. For each row in the Sollwerte table, compute the "Ist" values
#    by summing the corresponding hin- and rueck-link entries from the simulation
df <- soll

# Use dplyr for cleaner row-wise operations
download_missing_pkg <- function(pkg) {
  if (!require(pkg, character.only = TRUE)) install.packages(pkg, repos = "https://cloud.r-project.org/")
  library(pkg, character.only = TRUE)
}
download_missing_pkg("dplyr")

result <- df %>%
  rowwise() %>%
  mutate(
    Ist_Lkw = {
      vol_hin  <- sim$truck_volume[sim$link_id == linkID_hin]
      vol_rueck <- sim$truck_volume[sim$link_id == linkID_rueck]
      sum(c(vol_hin, vol_rueck), na.rm = TRUE)
    },
    Ist_Pkw = {
      car_hin  <- sim$vol_car[sim$link_id == linkID_hin]
      car_rueck <- sim$vol_car[sim$link_id == linkID_rueck]
      sum(c(car_hin, car_rueck), na.rm = TRUE)
    }
  ) %>%
  ungroup()

# Berechnung von Fehlerkennzahlen, GEH und SQV für LKW und PKW
# GEH: sqrt(2*(M - Z)^2/(M + Z)), SQV: 1/(1 + sqrt((M - Z)^2/(f * Z))), f = 10000
f <- 10000
result <- result %>%
  mutate(
    # LKW Fehler
    bias_Lkw      = Ist_Lkw - SollWertDTV_Lkw,
    abs_error_Lkw = abs(bias_Lkw),
    rel_error_Lkw = ifelse(SollWertDTV_Lkw != 0,
                           bias_Lkw / SollWertDTV_Lkw,
                           NA),
    GEH_Lkw       = sqrt(2 * bias_Lkw^2 / (Ist_Lkw + SollWertDTV_Lkw)),
    SQV_Lkw       = 1 / (1 + sqrt(bias_Lkw^2 / (f * SollWertDTV_Lkw))),
    # PKW Fehler
    bias_Pkw      = Ist_Pkw - SollWertDTV_Pkw,
    abs_error_Pkw = abs(bias_Pkw),
    rel_error_Pkw = ifelse(SollWertDTV_Pkw != 0,
                           bias_Pkw / SollWertDTV_Pkw,
                           NA),
    GEH_Pkw       = sqrt(2 * bias_Pkw^2 / (Ist_Pkw + SollWertDTV_Pkw)),
    SQV_Pkw       = 1 / (1 + sqrt(bias_Pkw^2 / (f * SollWertDTV_Pkw)))
  )

# Optionally, write the augmented table back to disk
output_file <- file.path(run_dir, "analysis", "traffic", "rvr_count_comparison.csv")
write.csv(result, output_file, row.names = FALSE, quote = FALSE)
message("Resulting CSV written to: ", output_file)


# Generierung von Verteilungsplots und Statistik-CSV **
# Für die Verteilungen nutzen wir Histogramme, da sie die Form der Daten am besten zeigen.
library(ggplot2)
library(tidyr)

# Daten in langes Format bringen
long <- result %>%
  pivot_longer(
    cols = ends_with(c("Lkw", "Pkw")),
    names_to = c("metric", "vehicle"),
    names_pattern = "^(.*)_(Lkw|Pkw)$",
    values_to = "value"
  )

# Statistische Grunddaten berechnen und als CSV speichern
stats <- long %>%
  group_by(vehicle, metric) %>%
  summarise(
    mean   = mean(value, na.rm = TRUE),
    median = median(value, na.rm = TRUE),
    sd     = sd(value, na.rm = TRUE),
    min    = min(value, na.rm = TRUE),
    max    = max(value, na.rm = TRUE),
    .groups = 'drop'
  )
summary_file <- file.path(run_dir, "analysis", "traffic", "rvr_count_comparison_stats_summary.csv")
write.csv(stats, summary_file, row.names = FALSE, quote = FALSE)
message("Summary stats written to: ", summary_file)

# Plots erzeugen und speichern
plots_dir <- file.path(run_dir, "analysis", "traffic", "plots")
if (!dir.exists(plots_dir)) dir.create(plots_dir)
metrics <- unique(long$metric)
vehicles <- unique(long$vehicle)
for (m in metrics) {
  for (v in vehicles) {
    df <- long %>% filter(metric == m, vehicle == v)
    p <- ggplot(df, aes(x = value)) +
      geom_histogram(bins = 30, color = "grey", fill = "lightblue") +
      labs(
        title = paste0(toupper(m), " Verteilung für ", v),
        x     = m,
        y     = "Häufigkeit"
      ) +
      theme_minimal()
    ggsave(
      filename = file.path(plots_dir, paste0(m, "_", v, ".png")),
      plot     = p,
      width    = 6,
      height   = 4
    )
  }
}
message("Plots saved in: ", plots_dir)
