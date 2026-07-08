#!/usr/bin/env Rscript

# Analyse der commercialTraffic-Endlaeufe fuer ein oder mehrere Szenarien.
#
# Das Script erzeugt CSV-Tabellen und PNG-Plots. Es beruecksichtigt die
# Sample-Groesse, indem zaehlende Werte auf 100% hochskaliert werden.

suppressPackageStartupMessages({
  library(dplyr)
  library(ggplot2)
  library(purrr)
  library(readr)
  library(stringr)
  library(tidyr)
})

# =============================================================================
# 1. Einstellungen: hier bei Bedarf Pfade und Ausgabeordner anpassen
# =============================================================================

repo_root <- normalizePath(getwd(), winslash = "/", mustWork = TRUE)

# Hier den Szenario-Namen anpassen. Er bestimmt im Standardfall sowohl den
# Input-Ordner output/<scenario_name> als auch den Analyseordner
# output/<scenario_name>_analysis.
scenario_name <- "studyWV_Ruhr"

# Direkte Pfade: in R-Strings funktionieren Windows-Pfade am besten mit "/"
# statt "\".
studyWV_Ruhr_dir <- "C:/Users/erica/shared/matsim-metropole-ruhr/output/studyWV_Ruhr"
studyWV_Ruhr_analysis_dir <- "C:/Users/erica/shared/matsim-metropole-ruhr/output/studyWV_Ruhr_analysis"

# Einzelnes Szenario:
scenario_paths <- c(
  studyWV_Ruhr = studyWV_Ruhr_dir
)

# Mehrere Szenarien/Runs koennen als benannter Vektor analysiert werden.
# Die Namen werden in CSVs und Plot-Facets als scenario_name genutzt.
# scenario_paths <- c(
#   base_2024 = "C:/Users/erica/shared/matsim-metropole-ruhr/output/studyWV_Ruhr",
#   sensitivity_2030 = "C:/Users/erica/shared/matsim-metropole-ruhr/output/studyWV_Ruhr_2030"
# )

out_dir <- studyWV_Ruhr_analysis_dir
csv_dir <- file.path(out_dir, "csv")
plot_dir <- file.path(out_dir, "plots")

# TRUE: Plots werden zusaetzlich zur PNG-Datei an das aktive R-Plot-Device
# geschickt. Das ist vor allem praktisch in RStudio oder einer interaktiven
# R-Konsole. Fuer IntelliJ das Script am besten in der R-Konsole ausfuehren
# oder sourcen, damit alle print(plot)-Aufrufe im Plot-Fenster landen.
show_plots_interactively <- TRUE

# Kurze Pause nach jedem print(plot), damit IDE-Plot-Viewer wie IntelliJ die
# Anzeige zuverlaessig aktualisieren koennen.
plot_display_pause_seconds <- 0.15

# =============================================================================
# 2. Hauptablauf: hier sieht man, was das Script nacheinander ausfuehrt
# =============================================================================

main <- function() {
  dir.create(csv_dir, recursive = TRUE, showWarnings = FALSE)
  dir.create(plot_dir, recursive = TRUE, showWarnings = FALSE)
  remove_obsolete_outputs()

  # 1) Run-Inventar erstellen: findet Run-Klassen, Sample-Ordner und
  # commercialTraffic_RunXpct-Endlaeufe.
  resolved_scenario_paths <- resolve_scenario_paths(scenario_paths, scenario_name, repo_root)
  message("Analyzing scenario path(s):")
  walk2(names(resolved_scenario_paths), resolved_scenario_paths, ~ message("  ", .x, ": ", .y))

  sample_dirs <- build_run_inventory(resolved_scenario_paths)
  write_csv(sample_dirs, file.path(csv_dir, "run_inventory.csv"), na = "")

  # Nur Runs mit vorhandenem analysis-Ordner werden fuer die Ergebnisanalyse
  # genutzt. Fehlende Endlaeufe bleiben im run_inventory sichtbar.
  analysis_runs <- sample_dirs %>%
    filter(has_analysis) %>%
    arrange(demand_model, fleet, sample_pct, run_class)

  message("Found ", nrow(analysis_runs), " final runs with analysis output.")

  # 2) Analyse-CSV aus den commercialTraffic_RunXpct-Endlaeufen lesen und
  # harmonisierte CSV-Sheets erzeugen.
  results <- build_csv_outputs(analysis_runs)

  # 3) Plots aus den harmonisierten Ergebnissen erzeugen.
  build_plots(results)

  message("Wrote CSV outputs to: ", csv_dir)
  message("Wrote plots to: ", plot_dir)
}

remove_obsolete_outputs <- function() {
  # Alte Dateien aus frueheren Script-Versionen loeschen, damit der Output-
  # Ordner nur die aktuell gewuenschten Analysen enthaelt.
  obsolete_files <- c(
    file.path(csv_dir, "mode_share_detailed.csv"),
    file.path(csv_dir, "mode_share_by_run.csv"),
    file.path(csv_dir, "mode_share_per_distance.csv"),
    file.path(csv_dir, "trip_purposes_by_hour.csv"),
    file.path(csv_dir, "travel_distance_shares_by_subpopulation.csv"),
    file.path(plot_dir, "mode_share_by_subpopulation.png"),
    file.path(plot_dir, "departures_by_hour_scaled.png"),
    file.path(plot_dir, "distance_share_by_subpopulation.png")
  )

  unlink(obsolete_files[file.exists(obsolete_files)])
}

# =============================================================================
# 3. Funktionen fuer Run-Erkennung und robustes CSV-Lesen
# =============================================================================

parse_run_class <- function(path) {
  run_class <- basename(path)

  tibble(
    run_class = run_class,
    fleet = case_when(
      str_detect(run_class, "(^|_)EV(_|$)") ~ "EV",
      str_detect(run_class, "(^|_)CV(_|$)") ~ "CV",
      TRUE ~ NA_character_
    ),
    demand_model = case_when(
      str_detect(run_class, "Basic") ~ "Basic",
      str_detect(run_class, "Advanced") ~ "Advanced",
      TRUE ~ NA_character_
    ),
    run_variant = case_when(
      str_detect(run_class, "splittedLTL") ~ "splittedLTL",
      TRUE ~ "standard"
    )
  )
}

parse_sample <- function(path) {
  sample_name <- basename(path)
  sample_pct <- str_match(sample_name, "commercial_([0-9.]+)pct")[, 2]

  tibble(
    sample_name = sample_name,
    sample_pct = as.numeric(sample_pct),
    sample_factor_to_100pct = if_else(
      is.na(sample_pct) | sample_pct == 0,
      NA_real_,
      100 / sample_pct
    )
  )
}

read_csv_auto <- function(path, col_types = cols(.default = col_guess())) {
  # MATSim schreibt je nach Analyse comma- oder semicolon-getrennte CSV.
  first_line <- readLines(path, n = 1, warn = FALSE)
  delim <- if_else(str_count(first_line, ";") > str_count(first_line, ","), ";", ",")

  read_delim(
    path,
    delim = delim,
    col_types = col_types,
    show_col_types = FALSE,
    progress = FALSE
  )
}

safe_read_csv_auto <- function(path) {
  if (!file.exists(path)) {
    return(tibble())
  }

  tryCatch(
    read_csv_auto(path),
    error = function(e) {
      warning("Could not read ", path, ": ", conditionMessage(e), call. = FALSE)
      tibble()
    }
  )
}

with_meta <- function(data, meta) {
  if (nrow(data) == 0) {
    return(data)
  }

  bind_cols(meta[rep(1, nrow(data)), ], data)
}

normalize_subpopulation <- function(x) {
  case_when(
    is.na(x) ~ NA_character_,
    x %in% c("commercialPersonTraffic_service", "Commercial Person Traffic Service") ~ "commercialPersonTraffic",
    TRUE ~ as.character(x)
  )
}

resolve_scenario_paths <- function(paths, default_scenario_name, repo_root) {
  if (length(paths) == 0 || all(is.na(paths) | !nzchar(paths))) {
    paths <- c(file.path(repo_root, "output", default_scenario_name))
  }

  path_labels <- names(paths)
  if (is.null(path_labels)) {
    path_labels <- rep("", length(paths))
  }

  path_labels <- if_else(
    is.na(path_labels) | !nzchar(path_labels),
    basename(paths),
    path_labels
  )

  if (length(paths) == 1 && path_labels[[1]] == basename(paths[[1]])) {
    path_labels[[1]] <- default_scenario_name
  }

  path_labels <- make.unique(path_labels, sep = "_")
  normalized_paths <- normalizePath(paths, winslash = "/", mustWork = FALSE)
  names(normalized_paths) <- path_labels

  missing_paths <- normalized_paths[!dir.exists(normalized_paths)]
  if (length(missing_paths) > 0) {
    warning(
      "Scenario path(s) not found and will be skipped: ",
      paste(paste0(names(missing_paths), "=", missing_paths), collapse = "; "),
      call. = FALSE
    )
  }

  existing_paths <- normalized_paths[dir.exists(normalized_paths)]
  if (length(existing_paths) == 0) {
    stop("No existing scenario path found. Please adjust scenario_paths at the top of the script.", call. = FALSE)
  }

  existing_paths
}

remove_basic_ftl_ltl <- function(data, columns) {
  # Im Basic-Ansatz werden FTL/LTL nicht separat erzeugt. Falls diese
  # Kategorien durch zusammengefuehrte Tabellen oder Plot-Facets auftauchen,
  # werden sie nur fuer Basic-Runs entfernt. Advanced bleibt unveraendert.
  existing_columns <- intersect(columns, names(data))
  if (length(existing_columns) == 0 || !("demand_model" %in% names(data))) {
    return(data)
  }

  ftl_ltl_pattern <- "^(FTL|LTL|LTL_.*)$"
  is_basic_ftl_ltl <- rep(FALSE, nrow(data))

  for (column in existing_columns) {
    is_basic_ftl_ltl <- is_basic_ftl_ltl |
      str_detect(as.character(data[[column]]), ftl_ltl_pattern)
  }

  data %>%
    filter(!(demand_model == "Basic" & is_basic_ftl_ltl))
}

build_run_inventory <- function(study_dirs) {
  map_dfr(seq_along(study_dirs), function(study_index) {
    study_dir <- unname(study_dirs[[study_index]])
    scenario_label <- names(study_dirs)[[study_index]]
    run_classes <- list.dirs(study_dir, recursive = FALSE, full.names = TRUE)

    map_dfr(run_classes, function(run_class_path) {
      sample_paths <- list.dirs(run_class_path, recursive = FALSE, full.names = TRUE)
      sample_paths <- sample_paths[str_detect(basename(sample_paths), "^commercial_[0-9.]+pct$")]

      map_dfr(sample_paths, function(sample_path) {
        run_meta <- parse_run_class(run_class_path)
        sample_meta <- parse_sample(sample_path)
        run_folder <- paste0("commercialTraffic_Run", sample_meta$sample_pct, "pct")
        final_run_path <- file.path(sample_path, run_folder)
        trips_path <- file.path(final_run_path, paste0(run_folder, ".output_trips.csv.gz"))

        bind_cols(run_meta, sample_meta) %>%
          mutate(
            scenario_name = scenario_label,
            scenario_path = normalizePath(study_dir, winslash = "/", mustWork = FALSE),
            run_class_path = normalizePath(run_class_path, winslash = "/", mustWork = FALSE),
            sample_path = normalizePath(sample_path, winslash = "/", mustWork = FALSE),
            final_run = run_folder,
            final_run_path = normalizePath(final_run_path, winslash = "/", mustWork = FALSE),
            analysis_path = normalizePath(file.path(final_run_path, "analysis"), winslash = "/", mustWork = FALSE),
            has_final_run = dir.exists(final_run_path),
            has_analysis = dir.exists(file.path(final_run_path, "analysis")),
            has_trips_csv = file.exists(trips_path),
            trips_csv_path = normalizePath(trips_path, winslash = "/", mustWork = FALSE)
          ) %>%
          relocate(scenario_name, scenario_path)
      })
    })
  })
}

read_analysis_file <- function(runs, relative_path) {
  map_dfr(seq_len(nrow(runs)), function(i) {
    meta <- runs[i, ] %>%
      select(
        scenario_name, scenario_path, run_class, fleet, demand_model, run_variant,
        sample_name, sample_pct, sample_factor_to_100pct, final_run, final_run_path
      )
    path <- file.path(runs$analysis_path[i], relative_path)
    data <- safe_read_csv_auto(path)

    with_meta(data, meta) %>% mutate(source_file = relative_path)
  })
}

# =============================================================================
# 4. Funktionen fuer fachliche Tabellen / CSV-Sheets
# =============================================================================

build_csv_outputs <- function(analysis_runs) {
  general_travel <- build_general_travel(analysis_runs)
  population_trip_stats <- build_population_trip_stats(analysis_runs)
  trip_stats <- build_trip_stats(analysis_runs)
  tour_distance_summary <- build_tour_distance_summary(analysis_runs)
  tour_duration_summary <- build_tour_duration_summary(analysis_runs)
  jobs_per_tour_summary <- build_jobs_per_tour_summary(analysis_runs)
  distance_shares <- build_distance_share_tables(analysis_runs)
  trips_csv_summary <- read_trips_csv_summary(analysis_runs)
  sample_comparison <- build_sample_comparison(trip_stats)

  write_csv(general_travel, file.path(csv_dir, "general_travel_summary.csv"), na = "")
  write_csv(population_trip_stats, file.path(csv_dir, "population_trip_stats_long.csv"), na = "")
  write_csv(trip_stats, file.path(csv_dir, "trip_stats_by_mode_long.csv"), na = "")
  write_csv(tour_distance_summary, file.path(csv_dir, "tour_distance_summary.csv"), na = "")
  write_csv(tour_duration_summary, file.path(csv_dir, "tour_duration_summary.csv"), na = "")
  write_csv(jobs_per_tour_summary, file.path(csv_dir, "jobs_per_tour_summary.csv"), na = "")
  write_csv(distance_shares$mode, file.path(csv_dir, "travel_distance_shares_by_mode.csv"), na = "")
  write_csv(distance_shares$group, file.path(csv_dir, "travel_distance_shares_by_group.csv"), na = "")
  write_csv(trips_csv_summary, file.path(csv_dir, "trips_csv_subpopulation_summary.csv"), na = "")
  write_csv(sample_comparison, file.path(csv_dir, "sample_comparison_1pct_vs_10pct.csv"), na = "")

  list(
    general_travel = general_travel,
    distance_share_group = distance_shares$group,
    tour_distance_summary = tour_distance_summary,
    sample_comparison = sample_comparison
  )
}

build_general_travel <- function(analysis_runs) {
  read_analysis_file(analysis_runs, file.path("commercialTraffic", "generalTravelData.csv")) %>%
    mutate(
      group = normalize_subpopulation(group),
      traveledDistance_km = as.numeric(traveledDistance_all) / 1000,
      scaled_numberOfAgents = as.numeric(numberOfAgents) * sample_factor_to_100pct,
      scaled_numberOfTrips = as.numeric(numberOfTrips_all) * sample_factor_to_100pct,
      scaled_traveledDistance_km = traveledDistance_km * sample_factor_to_100pct
    ) %>%
    group_by(
      scenario_name, scenario_path, run_class, fleet, demand_model, run_variant,
      sample_name, sample_pct, sample_factor_to_100pct, final_run,
      final_run_path, source_file, group
    ) %>%
    summarise(
      numberOfAgents = sum(as.numeric(numberOfAgents), na.rm = TRUE),
      numberOfTrips_all = sum(as.numeric(numberOfTrips_all), na.rm = TRUE),
      traveledDistance_all = sum(as.numeric(traveledDistance_all), na.rm = TRUE),
      averageTripsPerAgent_all = numberOfTrips_all / numberOfAgents,
      averageDistancePerTrip_all = traveledDistance_all / numberOfTrips_all,
      traveledDistance_km = sum(traveledDistance_km, na.rm = TRUE),
      scaled_numberOfAgents = sum(scaled_numberOfAgents, na.rm = TRUE),
      scaled_numberOfTrips = sum(scaled_numberOfTrips, na.rm = TRUE),
      scaled_traveledDistance_km = sum(scaled_traveledDistance_km, na.rm = TRUE),
      .groups = "drop"
    ) %>%
    remove_basic_ftl_ltl(c("group"))
}

build_population_trip_stats <- function(analysis_runs) {
  read_analysis_file(analysis_runs, file.path("population", "population_trip_stats.csv")) %>%
    pivot_longer(
      cols = -c(
        scenario_name, scenario_path, run_class, fleet, demand_model,
        run_variant, sample_name, sample_pct, sample_factor_to_100pct,
        final_run, final_run_path, source_file, Group
      ),
      names_to = "group",
      values_to = "value"
    ) %>%
    mutate(
      group = normalize_subpopulation(group),
      value = as.numeric(value)
    ) %>%
    remove_basic_ftl_ltl(c("group"))
}

build_trip_stats <- function(analysis_runs) {
  trip_files <- c(
    total = "trip_stats_total.csv",
    commercialTraffic = "trip_stats_commercialTraffic.csv",
    longDistanceFreight = "trip_stats_longDistanceFreight.csv",
    smallScaleGoodsTraffic = "trip_stats_smallScaleGoodsTraffic.csv",
    commercialPersonTraffic = "trip_stats_commercialPersonTraffic.csv"
  )

  map_dfr(names(trip_files), function(subpopulation_group) {
    data <- read_analysis_file(analysis_runs, file.path("population", trip_files[[subpopulation_group]]))
    if (nrow(data) == 0) {
      return(tibble())
    }

    data %>%
      mutate(subpopulation_group = normalize_subpopulation(subpopulation_group)) %>%
      pivot_longer(
        cols = -c(
          scenario_name, scenario_path, run_class, fleet, demand_model,
          run_variant, sample_name, sample_pct, sample_factor_to_100pct,
          final_run, final_run_path, source_file, subpopulation_group, Info
        ),
        names_to = "main_mode",
        values_to = "value"
      ) %>%
      mutate(
        value = as.numeric(value),
        scaled_value = if_else(
          Info %in% c("Number of trips", "Total time traveled [h]", "Total distance traveled [km]"),
          value * sample_factor_to_100pct,
          value
        )
      ) %>%
      remove_basic_ftl_ltl(c("subpopulation_group", "main_mode"))
  })
}

build_tour_distance_summary <- function(analysis_runs) {
  read_analysis_file(analysis_runs, file.path("commercialTraffic", "tourAnalysis_distances.csv")) %>%
    mutate(
      groupOfSubpopulation = normalize_subpopulation(groupOfSubpopulation),
      distanceInKm = as.numeric(distanceInKm),
      distanceInKmWithDepotCharging = as.numeric(distanceInKmWithDepotCharging)
    ) %>%
    group_by(
      scenario_name, scenario_path, run_class, fleet, demand_model, run_variant,
      sample_name, sample_pct, sample_factor_to_100pct, groupOfSubpopulation,
      vehicleType, dist_group
    ) %>%
    summarise(
      tours_sample = n(),
      tours_scaled_100pct = n() * first(sample_factor_to_100pct),
      avg_distance_km = mean(distanceInKm, na.rm = TRUE),
      median_distance_km = median(distanceInKm, na.rm = TRUE),
      total_distance_km_sample = sum(distanceInKm, na.rm = TRUE),
      total_distance_km_scaled_100pct = sum(distanceInKm, na.rm = TRUE) * first(sample_factor_to_100pct),
      .groups = "drop"
    ) %>%
    remove_basic_ftl_ltl(c("groupOfSubpopulation", "vehicleType"))
}

build_tour_duration_summary <- function(analysis_runs) {
  read_analysis_file(analysis_runs, file.path("commercialTraffic", "tourAnalysis_durations.csv")) %>%
    mutate(
      groupOfSubpopulation = normalize_subpopulation(groupOfSubpopulation),
      tourDurationInSeconds = as.numeric(tourDurationInSeconds),
      tourDurationsInHours = as.numeric(tourDurationsInHours)
    ) %>%
    group_by(
      scenario_name, scenario_path, run_class, fleet, demand_model, run_variant,
      sample_name, sample_pct, sample_factor_to_100pct, groupOfSubpopulation,
      vehicleType, duration_group
    ) %>%
    summarise(
      tours_sample = n(),
      tours_scaled_100pct = n() * first(sample_factor_to_100pct),
      avg_duration_h = mean(tourDurationsInHours, na.rm = TRUE),
      median_duration_h = median(tourDurationsInHours, na.rm = TRUE),
      .groups = "drop"
    ) %>%
    remove_basic_ftl_ltl(c("groupOfSubpopulation", "vehicleType"))
}

build_jobs_per_tour_summary <- function(analysis_runs) {
  read_analysis_file(analysis_runs, file.path("commercialTraffic", "tourAnalysis_jobsPerTour.csv")) %>%
    mutate(
      groupOfSubpopulation = normalize_subpopulation(groupOfSubpopulation),
      jobsPerTour = as.numeric(jobsPerTour)
    ) %>%
    group_by(
      scenario_name, scenario_path, run_class, fleet, demand_model, run_variant,
      sample_name, sample_pct, sample_factor_to_100pct, groupOfSubpopulation,
      vehicleType, numberOfJobs_group
    ) %>%
    summarise(
      tours_sample = n(),
      tours_scaled_100pct = n() * first(sample_factor_to_100pct),
      avg_jobs_per_tour = mean(jobsPerTour, na.rm = TRUE),
      .groups = "drop"
    ) %>%
    remove_basic_ftl_ltl(c("groupOfSubpopulation", "vehicleType"))
}

read_share_table <- function(analysis_runs, file_name, dimension_name) {
  read_analysis_file(analysis_runs, file.path("commercialTraffic", file_name)) %>%
    pivot_longer(
      cols = -c(
        scenario_name, scenario_path, run_class, fleet, demand_model,
        run_variant, sample_name, sample_pct, sample_factor_to_100pct,
        final_run, final_run_path, source_file
      ),
      names_to = dimension_name,
      values_to = "distance_share"
    ) %>%
    mutate(
      "{dimension_name}" := normalize_subpopulation(.data[[dimension_name]]),
      distance_share = as.numeric(distance_share)
    ) %>%
    group_by(
      scenario_name, scenario_path, run_class, fleet, demand_model, run_variant,
      sample_name, sample_pct, sample_factor_to_100pct, final_run,
      final_run_path, source_file, .data[[dimension_name]]
    ) %>%
    summarise(distance_share = sum(distance_share, na.rm = TRUE), .groups = "drop") %>%
    remove_basic_ftl_ltl(c(dimension_name))
}

build_distance_share_tables <- function(analysis_runs) {
  list(
    mode = read_share_table(
      analysis_runs,
      "travelDistancesShares_perMode.csv",
      "main_mode"
    ),
    group = read_share_table(
      analysis_runs,
      "travelDistancesShares_perGroup.csv",
      "groupOfSubpopulation"
    )
  )
}

infer_trip_subpopulation <- function(person) {
  case_when(
    str_detect(person, "commercialPersonTraffic") ~ "commercialPersonTraffic",
    str_detect(person, "goodsTraffic") ~ "smallScaleGoodsTraffic",
    str_detect(person, "longDistanceFreight|freight_") ~ "longDistanceFreight",
    str_detect(person, "FTL") ~ "FTL",
    str_detect(person, "LTL") ~ "LTL",
    str_detect(person, "Parcel") ~ "Parcel",
    str_detect(person, "Waste") ~ "Waste",
    TRUE ~ "unknown"
  )
}

read_trips_csv_summary <- function(analysis_runs) {
  # Diese Zusatzanalyse nutzt output_trips.csv.gz, falls vorhanden. In manchen
  # Runs fehlen diese Dateien; dann bleibt die Tabelle fuer diese Runs leer.
  trip_runs <- analysis_runs %>% filter(has_trips_csv)

  map_dfr(seq_len(nrow(trip_runs)), function(i) {
    meta <- trip_runs[i, ] %>%
      select(
        scenario_name, scenario_path, run_class, fleet, demand_model,
        run_variant, sample_name, sample_pct, sample_factor_to_100pct,
        final_run, final_run_path
      )
    trips <- safe_read_csv_auto(trip_runs$trips_csv_path[i])
    if (nrow(trips) == 0 || !("person" %in% names(trips))) {
      return(tibble())
    }

    distance_col <- intersect(c("traveled_distance", "trav_distance", "distance"), names(trips))[1]
    time_col <- intersect(c("trav_time", "travel_time"), names(trips))[1]

    trips %>%
      mutate(
        inferred_subpopulation = infer_trip_subpopulation(person),
        main_mode = if ("main_mode" %in% names(.)) main_mode else NA_character_,
        traveled_distance_m = if (!is.na(distance_col)) as.numeric(.data[[distance_col]]) else NA_real_,
        traveled_distance_km = traveled_distance_m / 1000,
        travel_time_raw = if (!is.na(time_col)) as.character(.data[[time_col]]) else NA_character_
      ) %>%
      group_by(inferred_subpopulation, main_mode) %>%
      summarise(
        agents_sample = n_distinct(person),
        trips_sample = n(),
        trips_scaled_100pct = n() * meta$sample_factor_to_100pct,
        total_distance_km_sample = sum(traveled_distance_km, na.rm = TRUE),
        total_distance_km_scaled_100pct = sum(traveled_distance_km, na.rm = TRUE) * meta$sample_factor_to_100pct,
        avg_trip_distance_km = mean(traveled_distance_km, na.rm = TRUE),
        .groups = "drop"
      ) %>%
      with_meta(meta) %>%
      remove_basic_ftl_ltl(c("inferred_subpopulation", "main_mode"))
  })
}

build_sample_comparison <- function(trip_stats) {
  sample_comparison <- trip_stats %>%
    filter(Info == "Number of trips", subpopulation_group != "total") %>%
    select(
      scenario_name, scenario_path, run_class, fleet, demand_model,
      run_variant, sample_pct, subpopulation_group, main_mode, scaled_value
    ) %>%
    group_by(
      scenario_name, scenario_path, fleet, demand_model, run_variant,
      subpopulation_group, main_mode, sample_pct
    ) %>%
    summarise(scaled_trips_100pct = sum(scaled_value, na.rm = TRUE), .groups = "drop") %>%
    pivot_wider(names_from = sample_pct, values_from = scaled_trips_100pct, names_prefix = "sample_")

  if (!("sample_1" %in% names(sample_comparison))) {
    sample_comparison$sample_1 <- NA_real_
  }
  if (!("sample_10" %in% names(sample_comparison))) {
    sample_comparison$sample_10 <- NA_real_
  }

  sample_comparison %>%
    mutate(
      diff_10pct_minus_1pct = sample_10 - sample_1,
      rel_diff_10pct_vs_1pct = diff_10pct_minus_1pct / sample_1
    ) %>%
    remove_basic_ftl_ltl(c("subpopulation_group", "main_mode"))
}

# =============================================================================
# 5. Funktionen fuer Visualisierungen
# =============================================================================

save_plot <- function(plot, file_name, width = 16, height = 9) {
  if (isTRUE(show_plots_interactively)) {
    print(plot)
    try(grDevices::dev.flush(), silent = TRUE)
    if (plot_display_pause_seconds > 0) {
      Sys.sleep(plot_display_pause_seconds)
    }
  }

  ggsave(
    filename = file.path(plot_dir, file_name),
    plot = plot,
    width = width,
    height = height,
    dpi = 360,
    bg = "white",
    limitsize = FALSE
  )
}

facet_formula <- function(data, rows, cols, scenario_side = "columns") {
  has_multiple_scenarios <- "scenario_name" %in% names(data) &&
    n_distinct(data$scenario_name, na.rm = TRUE) > 1

  if (has_multiple_scenarios && scenario_side == "columns") {
    cols <- paste("scenario_name", cols, sep = " + ")
  } else if (has_multiple_scenarios && scenario_side == "rows") {
    rows <- paste("scenario_name", rows, sep = " + ")
  }

  as.formula(paste(rows, "~", cols))
}

label_number <- function(x, digits = 1, suffix = "") {
  paste0(scales::number(x, accuracy = 10^-digits, big.mark = " "), suffix)
}

label_percent <- function(x, digits = 1) {
  scales::percent(x, accuracy = 10^-digits)
}

plot_theme <- theme_minimal(base_size = 11) +
  theme(
    legend.position = "bottom",
    panel.grid.minor = element_blank(),
    axis.text.x = element_text(angle = 35, hjust = 1)
  )

build_plots <- function(results) {
  plot_scaled_travel_distance(results$general_travel)
  plot_distance_share(results$distance_share_group)
  plot_average_tour_distance(results$tour_distance_summary)
  plot_sample_difference(results$sample_comparison)
}

plot_scaled_travel_distance <- function(general_travel) {
  if (nrow(general_travel) == 0) {
    return(invisible(NULL))
  }

  dodge <- position_dodge(width = 0.8)

  p <- general_travel %>%
    mutate(plot_value = scaled_traveledDistance_km / 1e6) %>%
    ggplot(aes(x = group, y = plot_value, fill = fleet)) +
    geom_col(position = dodge) +
    geom_text(
      aes(label = label_number(plot_value, digits = 1)),
      position = dodge,
      vjust = -0.25,
      size = 3
    ) +
    facet_grid(facet_formula(general_travel, "sample_pct", "demand_model"), labeller = label_both) +
    scale_y_continuous(expand = expansion(mult = c(0, 0.16))) +
    labs(
      title = "Scaled travel distance by fleet, demand model and sample",
      x = "Subpopulation group",
      y = "Travel distance scaled to 100% [million km]",
      fill = "Fleet"
    ) +
    plot_theme

  save_plot(p, "scaled_travel_distance_by_group.png")
}

plot_distance_share <- function(distance_share_group) {
  if (nrow(distance_share_group) == 0) {
    return(invisible(NULL))
  }

  dodge <- position_dodge(width = 0.8)

  p <- distance_share_group %>%
    ggplot(aes(x = groupOfSubpopulation, y = distance_share, fill = fleet)) +
    geom_col(position = dodge) +
    geom_text(
      aes(label = label_percent(distance_share, digits = 1)),
      position = dodge,
      vjust = -0.25,
      size = 3
    ) +
    facet_grid(facet_formula(distance_share_group, "sample_pct", "demand_model"), labeller = label_both) +
    scale_y_continuous(labels = scales::percent_format(), expand = expansion(mult = c(0, 0.16))) +
    labs(
      title = "Travel distance shares by subpopulation group",
      x = "Subpopulation group",
      y = "Share of travel distance",
      fill = "Fleet"
    ) +
    plot_theme

  save_plot(p, "distance_share_by_subpopulation_group.png", width = 14, height = 8)
}

plot_average_tour_distance <- function(tour_distance_summary) {
  if (nrow(tour_distance_summary) == 0) {
    return(invisible(NULL))
  }

  dodge <- position_dodge(width = 0.8)

  p <- tour_distance_summary %>%
    group_by(scenario_name, scenario_path, fleet, demand_model, sample_pct, groupOfSubpopulation) %>%
    summarise(avg_distance_km = weighted.mean(avg_distance_km, tours_sample, na.rm = TRUE), .groups = "drop") %>%
    ggplot(aes(x = groupOfSubpopulation, y = avg_distance_km, fill = fleet)) +
    geom_col(position = dodge) +
    geom_text(
      aes(label = label_number(avg_distance_km, digits = 1)),
      position = dodge,
      vjust = -0.25,
      size = 3
    ) +
    facet_grid(facet_formula(tour_distance_summary, "sample_pct", "demand_model"), labeller = label_both) +
    scale_y_continuous(expand = expansion(mult = c(0, 0.16))) +
    labs(
      title = "Average tour distance",
      x = "Subpopulation group",
      y = "Average tour distance [km]",
      fill = "Fleet"
    ) +
    plot_theme

  save_plot(p, "average_tour_distance.png")
}

plot_sample_difference <- function(sample_comparison) {
  if (nrow(sample_comparison) == 0 || !("rel_diff_10pct_vs_1pct" %in% names(sample_comparison))) {
    return(invisible(NULL))
  }

  sample_plot_data <- sample_comparison %>%
    filter(!is.na(rel_diff_10pct_vs_1pct), is.finite(rel_diff_10pct_vs_1pct)) %>%
    mutate(label_vjust = if_else(rel_diff_10pct_vs_1pct >= 0, -0.25, 1.2))

  p <- sample_plot_data %>%
    ggplot(aes(x = main_mode, y = rel_diff_10pct_vs_1pct, fill = subpopulation_group)) +
    geom_hline(yintercept = 0, color = "grey45") +
    geom_col(position = position_dodge(width = 0.8)) +
    geom_text(
      aes(label = label_percent(rel_diff_10pct_vs_1pct, digits = 1), vjust = label_vjust),
      position = position_dodge(width = 0.8),
      size = 3
    ) +
    facet_grid(facet_formula(sample_plot_data, "demand_model", "fleet", scenario_side = "rows"), labeller = label_both) +
    scale_y_continuous(labels = scales::percent_format(), expand = expansion(mult = c(0.16, 0.16))) +
    labs(
      title = "Relative difference of scaled trip counts: 10% sample vs 1% sample",
      x = "Mode",
      y = "(10% scaled - 1% scaled) / 1% scaled",
      fill = "Subpopulation group"
    ) +
    plot_theme

  save_plot(p, "sample_difference_10pct_vs_1pct.png", width = 14, height = 8)
}

# =============================================================================
# 6. Ausfuehrung
# =============================================================================

main()
