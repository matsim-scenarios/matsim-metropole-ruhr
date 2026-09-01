#!/usr/bin/env Rscript

# Analyse der paper-relevanten Parameter eines WV-Ruhr-Runs.
#
library(dplyr)
library(purrr)
library(readr)
library(stringr)
library(tibble)
library(tidyr)

# =============================================================================
# 1. Einstellungen: hier wird der Run ausgewaehlt
# =============================================================================

# Hier den Studienordner eintragen.
# In R-Strings funktionieren Windows-Pfade am besten mit "/" statt "\".
run_folder_url <- "C:/Users/erica/shared/matsim-metropole-ruhr/output/studyWV_Ruhr"

year <- 2024
# year <- 2030
# year <- 2050

fleet_type <- "CV"
# fleet_type <- "EV"
# fleet_type <- "Mixed"

model_type <- "Basic"
# model_type <- "Advanced"

# sample_pct <- 10
sample_pct <- 1
# sample_pct <- 25

scenario_prefix <- "studyWV_Ruhr_new"
workdays_per_year <- 260

# Fuer WTW werden alle drei Strommix-/Faktorjahre geschrieben.
emission_factor_years <- c(2021, 2030, 2050)

# Fuer Kosten-Sensitivitaeten koennen dieselben ausgewaehlten Plaene mit
# Kostensaetzen anderer Jahre neu bewertet werden. Bei 2024 werden die
# Paper-Preisjahre 2024, 2030 und 2050 geschrieben.
cost_result_years <- if (year == 2024) c(2024, 2030, 2050) else year

# Optional: Falls Preisjahr-Dateien nicht in der erwarteten Run-Struktur liegen,
# koennen sie hier pro Jahr explizit eingetragen werden. Relative Pfade werden
# relativ zum Studienordner run_folder_url aufgeloest.
cost_vehicle_type_file_overrides <- c(
  "2030" = "C:/Users/erica/shared/runs-svn/rvr-ruhrgebiet/2026_StudyDecarbonizationCommercialTraffic/input/metropole-ruhr-v2024.2.mode-vehicles_WV_base2030_Mixed.xml",
  "2050" = "C:/Users/erica/shared/runs-svn/rvr-ruhrgebiet/2026_StudyDecarbonizationCommercialTraffic/input/metropole-ruhr-v2024.2.mode-vehicles_WV_base2050_Mixed.xml"
)

# =============================================================================
# 2. Hauptablauf
# =============================================================================

main <- function() {
  paths <- resolve_selected_run(
    root_url = run_folder_url,
    selected_year = year,
    selected_fleet = fleet_type,
    selected_model = model_type,
    selected_sample_pct = sample_pct,
    prefix = scenario_prefix
  )

  output_root <- file.path(
    dirname(paths$final_run_dir),
    paste0("paper_parameters_", paths$scenario_name, "_", paths$sample_tag, "pct")
  )
  csv_dir <- file.path(output_root, "csv")
  dir.create(csv_dir, recursive = TRUE, showWarnings = FALSE)

  message("Analyzing final run: ", paths$final_run_dir)
  message("Writing parameter analysis to: ", output_root)

  message("Reading MATSim analysis CSVs...")
  analysis_files <- read_matsim_analysis_tables(paths)
  message("Reading person scores...")
  person_scores <- read_person_scores(paths)
  message("Reading trips...")
  trip_measures <- read_trip_measures(paths, person_scores)
  message("Reading selected-plan distance and travel time...")
  selected_plan_measures <- read_selected_plan_measures(paths, person_scores)
  message("Reading selected-plan cost inputs...")
  selected_plan_cost_inputs <- read_selected_plan_cost_inputs(paths, person_scores)
  selected_plan_cost_legs <- selected_plan_cost_inputs$legs
  selected_plan_cost_activities <- selected_plan_cost_inputs$activities
  message("Reading vehicle cost parameters for price years...")
  cost_vehicle_parameters <- read_cost_vehicle_parameters(paths, cost_result_years)
  message("Reading carrier and jsprit outputs...")
  carrier_outputs <- read_carrier_outputs(paths)
  range_logs <- tibble()
  if (should_build_bev_range_feasibility(paths)) {
    message("Reading range and recharge logs...")
    range_log_scope <- build_range_log_start_scope(analysis_files$tour_distances, person_scores)
    range_logs <- read_range_recharge_logs(paths, range_log_scope)
  }
  energy_emissions <- build_energy_emissions(
    analysis_files$tour_distances,
    paths,
    selected_plan_cost_legs
  )
  paper_costs_by_component <- build_paper_costs_by_component(
    selected_plan_cost_legs,
    selected_plan_cost_activities,
    selected_plan_measures,
    cost_vehicle_parameters,
    analysis_files$tour_distances,
    paths
  )

  paper_key_facts_by_component <- build_paper_key_facts_by_component(
    analysis_files$tour_distances,
    analysis_files$tour_durations,
    trip_measures,
    person_scores,
    selected_plan_measures,
    paper_costs_by_component,
    paths
  )

  paper_key_facts_total <- build_paper_key_facts_total(paper_key_facts_by_component)
  paper_comparable_costs <- build_paper_comparable_costs(paper_key_facts_by_component, paths)
  validate_paper_analysis_outputs(
    analysis_files$tour_distances,
    person_scores,
    energy_emissions,
    paper_costs_by_component,
    paper_key_facts_by_component,
    paths
  )

  paper_tables <- build_paper_table_outputs(
    tour_distances = analysis_files$tour_distances,
    tour_durations = analysis_files$tour_durations,
    jobs_per_tour = analysis_files$jobs_per_tour,
    person_scores = person_scores,
    selected_plan_cost_legs = selected_plan_cost_legs,
    energy_emissions = energy_emissions,
    paper_costs_by_component = paper_costs_by_component,
    paper_key_facts_by_component = paper_key_facts_by_component,
    paper_key_facts_total = paper_key_facts_total,
    paper_comparable_costs = paper_comparable_costs,
    carrier_kpis = carrier_outputs$kpis,
    carrier_time_distance_vehicle_type = carrier_outputs$time_distance_vehicle_type,
    range_logs = range_logs,
    paths = paths
  )

  clean_csv_dir_for_paper_tables(csv_dir, names(paper_tables))
  write_named_tables(paper_tables, csv_dir)

  message("Done. CSV outputs: ", csv_dir)
}

# =============================================================================
# 3. Pfade, Meta-Daten und allgemeine Helfer
# =============================================================================

add_warning <- function(...) {
  message_text <- paste0(...)
  warning(message_text, call. = FALSE)
}

format_sample_pct <- function(x) {
  formatted <- format(as.numeric(x), trim = TRUE, scientific = FALSE)
  sub("\\.0+$", "", formatted)
}

normalize_user_path <- function(path) {
  gsub("\\\\", "/", trimws(path))
}

resolve_selected_run <- function(root_url, selected_year, selected_fleet, selected_model,
                                 selected_sample_pct, prefix) {
  root <- normalize_user_path(root_url)
  sample_tag <- format_sample_pct(selected_sample_pct)
  sample_dir_name <- paste0("commercial_", sample_tag, "pct")
  final_run_name <- paste0("commercialTraffic_Run", sample_tag, "pct")
  scenario_name <- paste(prefix, selected_fleet, selected_model, selected_year, sep = "_")

  sample_dir <- file.path(root, scenario_name, sample_dir_name)
  final_run_dir <- file.path(sample_dir, final_run_name)
  if (!dir.exists(final_run_dir)) {
    stop(
      "Could not find final run folder: ",
      final_run_dir,
      "\nExpected structure: run_folder_url/scenario/commercial_Xpct/commercialTraffic_RunXpct.",
      call. = FALSE
    )
  }

  final_run_dir <- normalizePath(final_run_dir, winslash = "/", mustWork = TRUE)
  sample_dir <- normalizePath(dirname(final_run_dir), winslash = "/", mustWork = TRUE)
  scenario_dir <- normalizePath(dirname(sample_dir), winslash = "/", mustWork = TRUE)
  study_dir <- normalizePath(dirname(scenario_dir), winslash = "/", mustWork = FALSE)

  list(
    input_url = root,
    study_dir = study_dir,
    scenario_dir = scenario_dir,
    sample_dir = sample_dir,
    final_run_dir = final_run_dir,
    analysis_dir = file.path(final_run_dir, "analysis"),
    generated_input_dir = file.path(scenario_dir, "generatedInputData"),
    small_scale_dir = file.path(sample_dir, "smallScaleCommercial"),
    slurm_log_dir = file.path(sample_dir, "slurmLogs"),
    scenario_name = basename(scenario_dir),
    requested_scenario_name = scenario_name,
    year = as.integer(selected_year),
    fleet_type = selected_fleet,
    model_type = selected_model,
    sample_pct = as.numeric(selected_sample_pct),
    sample_tag = sample_tag,
    sample_factor_to_100pct = 100 / as.numeric(selected_sample_pct),
    final_run_name = basename(final_run_dir)
  )
}

analysis_meta <- function(paths) {
  tibble(
    scenario_name = paths$scenario_name,
    year = paths$year,
    fleet_type = paths$fleet_type,
    model_type = paths$model_type,
    sample_pct = paths$sample_pct,
    sample_factor_to_100pct = paths$sample_factor_to_100pct,
    final_run_dir = paths$final_run_dir
  )
}

with_meta <- function(data, paths, source_file = NA_character_) {
  if (nrow(data) == 0) {
    return(data)
  }

  bind_cols(analysis_meta(paths)[rep(1, nrow(data)), ], data) %>%
    mutate(source_file = source_file)
}

copy_analysis_meta <- function(summary, source_data) {
  if (nrow(summary) == 0 || nrow(source_data) == 0) {
    return(summary)
  }

  meta_cols <- c("scenario_name", "year", "fleet_type", "model_type", "sample_pct", "sample_factor_to_100pct", "final_run_dir")
  existing_meta_cols <- intersect(meta_cols, names(source_data))
  if (length(existing_meta_cols) == 0) {
    return(summary)
  }

  meta <- source_data %>%
    select(all_of(existing_meta_cols)) %>%
    slice(1)

  bind_cols(meta[rep(1, nrow(summary)), , drop = FALSE], summary)
}

count_delim <- function(pattern, x) {
  matches <- gregexpr(pattern, x, fixed = TRUE)[[1]]
  if (identical(matches, -1L)) 0L else length(matches)
}

open_text_connection <- function(path) {
  if (grepl("\\.gz$", path, ignore.case = TRUE)) {
    return(gzfile(path, open = "rt"))
  }

  if (grepl("\\.zst$", path, ignore.case = TRUE)) {
    command <- zstd_stream_command(path)
    if (is.na(command)) {
      stop(
        "Cannot read Zstandard-compressed file without zstd, unzstd or 7-Zip: ",
        path,
        call. = FALSE
      )
    }
    return(pipe(command, open = "rt"))
  }

  file(path, open = "rt")
}

# Builds a shell command that streams a .zst file as text.
zstd_stream_command <- function(path) {
  zstd <- Sys.which("zstd")
  if (!is.na(zstd) && nzchar(zstd)) {
    return(suppress_command_stderr(paste(command_arg(zstd), "-dc", command_arg(path))))
  }

  unzstd <- Sys.which("unzstd")
  if (!is.na(unzstd) && nzchar(unzstd)) {
    return(suppress_command_stderr(paste(command_arg(unzstd), "-c", command_arg(path))))
  }

  seven_zip_candidates <- c(
    Sys.which("7z"),
    "C:/Program Files/7-Zip/7z.exe",
    "C:/Program Files (x86)/7-Zip/7z.exe"
  )
  seven_zip <- seven_zip_candidates[nzchar(seven_zip_candidates) & file.exists(seven_zip_candidates)]
  if (length(seven_zip) > 0) {
    return(suppress_command_stderr(paste(command_arg(seven_zip[[1]]), "x -so", command_arg(path))))
  }

  NA_character_
}

# Redirects decompressor stderr so early first-line reads do not print broken-pipe noise.
suppress_command_stderr <- function(command) {
  null_device <- if (.Platform$OS.type == "windows") "NUL" else "/dev/null"
  paste(command, paste0("2>", null_device))
}

# Quotes command arguments, except for Windows short paths without spaces where R pipe quoting is fragile.
command_arg <- function(path) {
  path <- command_path(path)
  if (.Platform$OS.type == "windows" && !grepl("\\s", path)) {
    return(path)
  }

  shQuote(path)
}

# Uses Windows short paths when available so pipe commands survive spaces in Program Files.
command_path <- function(path) {
  normalized <- normalizePath(path, winslash = "/", mustWork = FALSE)
  if (.Platform$OS.type == "windows" && file.exists(normalized)) {
    return(utils::shortPathName(normalized))
  }

  normalized
}

read_first_line <- function(path) {
  con <- open_text_connection(path)
  on.exit(close(con), add = TRUE)
  line <- readLines(con, n = 1, warn = FALSE)
  if (length(line) == 0) "" else line[[1]]
}

detect_delim <- function(path) {
  first_line <- read_first_line(path)
  counts <- c(
    tab = count_delim("\t", first_line),
    semicolon = count_delim(";", first_line),
    comma = count_delim(",", first_line)
  )

  dplyr::recode(names(which.max(counts)), tab = "\t", semicolon = ";", comma = ",")
}

read_delim_auto <- function(path, col_names = TRUE) {
  delim <- detect_delim(path)
  con <- open_text_connection(path)
  on.exit(close(con), add = TRUE)

  read_args <- list(
    file = con,
    sep = delim,
    header = identical(col_names, TRUE),
    stringsAsFactors = FALSE,
    quote = "",
    comment.char = "",
    check.names = FALSE,
    na.strings = c("", "NA", "NaN", "null"),
    colClasses = "character",
    fill = TRUE,
    skipNul = TRUE
  )

  if (is.character(col_names)) {
    read_args$header <- FALSE
    read_args$col.names <- col_names
  } else if (identical(col_names, FALSE)) {
    read_args$header <- FALSE
  }

  tibble::as_tibble(
    do.call(utils::read.table, read_args),
    .name_repair = "minimal"
  )
}

safe_read_delim_auto <- function(path, col_names = TRUE) {
  if (!file.exists(path)) {
    add_warning("Missing file: ", path)
    return(tibble())
  }

  tryCatch(
    read_delim_auto(path, col_names = col_names),
    error = function(e) {
      add_warning("Could not read ", path, ": ", conditionMessage(e))
      tibble()
    }
  )
}

write_named_tables <- function(tables, csv_dir) {
  purrr::iwalk(tables, function(data, name) {
    if (is.null(data)) {
      data <- tibble()
    }

    path <- file.path(csv_dir, paste0(name, ".csv"))
    if (ncol(data) == 0) {
      data <- tibble(note = character())
    }

    write_csv_file(data, path)
  })
}

clean_csv_dir_for_paper_tables <- function(csv_dir, table_names) {
  if (!dir.exists(csv_dir)) {
    return(invisible(FALSE))
  }

  expected_files <- paste0(table_names, ".csv")
  csv_files <- list.files(csv_dir, pattern = "\\.csv$", full.names = TRUE)
  obsolete_files <- csv_files[!basename(csv_files) %in% expected_files]
  if (length(obsolete_files) > 0) {
    unlink(obsolete_files)
  }

  invisible(TRUE)
}

write_csv_file <- function(data, path, na = "") {
  dir.create(dirname(path), recursive = TRUE, showWarnings = FALSE)
  readr::write_csv(data, path, na = na)
}

normalize_names <- function(data) {
  names(data) <- tolower(gsub("[^A-Za-z0-9]+", "_", names(data)))
  names(data) <- gsub("^_|_$", "", names(data))
  names(data) <- gsub("_+", "_", names(data))
  data
}

as_number <- function(x) {
  if (is.numeric(x)) {
    return(x)
  }

  text <- trimws(as.character(x))
  text[text %in% c("", "NA", "NaN", "null")] <- NA_character_
  suppressWarnings(as.numeric(gsub(",", "", text, fixed = TRUE)))
}

safe_sum <- function(x) {
  values <- x[!is.na(x)]
  if (length(values) == 0) {
    return(NA_real_)
  }
  sum(values)
}

safe_mean <- function(x) {
  values <- x[!is.na(x)]
  if (length(values) == 0) {
    return(NA_real_)
  }
  mean(values)
}

safe_max <- function(x) {
  values <- x[!is.na(x)]
  if (length(values) == 0) {
    return(NA_real_)
  }
  max(values)
}

safe_median <- function(x) {
  values <- x[!is.na(x)]
  if (length(values) == 0) {
    return(NA_real_)
  }
  stats::median(values)
}

first_non_missing <- function(x) {
  values <- x[!is.na(x)]
  if (length(values) == 0) {
    return(NA)
  }
  values[[1]]
}

safe_divide <- function(numerator, denominator) {
  ifelse(is.na(denominator) | denominator == 0, NA_real_, numerator / denominator)
}

with_boundary_only <- function(in_simulation, with_boundary) {
  ifelse(
    is.na(in_simulation) | is.na(with_boundary) | abs(with_boundary - in_simulation) < 1e-9,
    NA_real_,
    with_boundary
  )
}

clean_subpopulation <- function(x) {
  x <- as.character(x)
  x[is.na(x) | trimws(x) == ""] <- "unknown"
  x[x == "commercialPersonTraffic_service"] <- "commercialPersonTraffic"
  x
}

ltl_components <- function() {
  c("wasteCollection", "CEP", "remainingLTL")
}

is_advanced_model <- function(paths) {
  identical(as.character(paths$model_type), "Advanced")
}

is_bev_fleet <- function(paths) {
  identical(as.character(paths$fleet_type), "EV")
}

is_mixed_fleet <- function(paths) {
  identical(as.character(paths$fleet_type), "Mixed")
}

should_build_bev_range_feasibility <- function(paths) {
  is_bev_fleet(paths)
}

should_build_ltl_analyses <- function(paths) {
  is_advanced_model(paths)
}

should_build_recharge_vehicle_use <- function(paths) {
  is_bev_fleet(paths)
}

should_build_mixed_ltl_tables <- function(paths) {
  should_build_ltl_analyses(paths) && is_mixed_fleet(paths)
}

parse_time_to_seconds <- function(x) {
  vapply(as.character(x), parse_one_time_to_seconds, numeric(1), USE.NAMES = FALSE)
}

parse_one_time_to_seconds <- function(value) {
  value <- trimws(value)

  if (is.na(value) || value == "" || value %in% c("NA", "NaN", "null")) {
    return(NA_real_)
  }

  if (grepl("^[-+]?[0-9]+([.][0-9]+)?$", value)) {
    return(as.numeric(value))
  }

  parts <- strsplit(value, ":", fixed = TRUE)[[1]]
  numeric_parts <- suppressWarnings(as.numeric(parts))

  if (any(is.na(numeric_parts))) {
    return(NA_real_)
  }

  if (length(numeric_parts) == 3) {
    return(numeric_parts[[1]] * 3600 + numeric_parts[[2]] * 60 + numeric_parts[[3]])
  }
  if (length(numeric_parts) == 2) {
    return(numeric_parts[[1]] * 60 + numeric_parts[[2]])
  }
  if (length(numeric_parts) == 1) {
    return(numeric_parts[[1]])
  }

  NA_real_
}

parse_duration_hms_to_seconds <- function(x) {
  parse_time_to_seconds(sub("\\..*$", "", as.character(x)))
}

normalize_vehicle_type <- function(vehicle_type) {
  sub("_[0-9]+Recharge$", "", as.character(vehicle_type))
}

is_recharge_vehicle_type <- function(vehicle_type) {
  grepl("Recharge$", as.character(vehicle_type), ignore.case = TRUE)
}

recharge_level <- function(vehicle_type) {
  text <- as.character(vehicle_type)
  level <- suppressWarnings(as.integer(str_match(text, "_([0-9]+)Recharge$")[, 2]))
  ifelse(is.na(level) & is_recharge_vehicle_type(text), 1L, level)
}

# =============================================================================
# 4. MATSim-Analyse-CSV lesen und zusammenfassen
# =============================================================================

read_matsim_analysis_tables <- function(paths) {
  analysis_path <- paths$analysis_dir

  list(
    tour_distances = safe_read_delim_auto(file.path(analysis_path, "commercialTraffic", "tourAnalysis_distances.csv")),
    tour_durations = safe_read_delim_auto(file.path(analysis_path, "commercialTraffic", "tourAnalysis_durations.csv")),
    jobs_per_tour = safe_read_delim_auto(file.path(analysis_path, "commercialTraffic", "tourAnalysis_jobsPerTour.csv"))
  )
}

# =============================================================================
# 5. Personen, Trips, Scores und Fahrzeugnutzung
# =============================================================================

find_run_file <- function(paths, suffix) {
  path <- file.path(paths$final_run_dir, paste0(paths$final_run_name, as.character(suffix)))
  if (!file.exists(path)) {
    return(NA_character_)
  }

  normalizePath(path, winslash = "/", mustWork = TRUE)
}

# Creates a person lookup with the requested columns even when person scores are unavailable.
build_person_lookup <- function(person_scores, requested_cols) {
  if (nrow(person_scores) == 0) {
    stop("Person lookup cannot be built from an empty person-score table.", call. = FALSE)
  }

  person_scores %>%
    select(all_of(requested_cols)) %>%
    distinct()
}

read_person_scores <- function(paths) {
  persons_file <- find_run_file(paths, ".output_persons.csv.zst")
  if (is.na(persons_file)) {
    stop("Missing output_persons.csv.zst in final run: ", paths$final_run_dir, call. = FALSE)
  }

  persons <- safe_read_delim_auto(persons_file)
  if (nrow(persons) == 0) {
    stop("output_persons.csv.zst is empty: ", persons_file, call. = FALSE)
  }

  required <- c("person", "executed_score", "subpopulation", "carrierId", "tourId", "goods_type", "vehicleTypes")
  missing <- setdiff(required, names(persons))
  if (length(missing) > 0) {
    stop("Persons file misses required columns: ", paste(missing, collapse = ", "), call. = FALSE)
  }

  persons %>%
    transmute(
      person = as.character(.data$person),
      subpopulation = clean_subpopulation(.data$subpopulation),
      executed_score = as_number(.data$executed_score),
      cost = -1 * executed_score,
      carrier_id = as.character(.data$carrierId),
      tour_id = as.character(.data$tourId),
      goods_type = as_number(.data$goods_type),
      vehicle_types_raw = as.character(.data$vehicleTypes)
    ) %>%
    with_meta(paths, basename(persons_file))
}

read_trip_measures <- function(paths, person_scores) {
  trips_file <- find_run_file(paths, ".output_trips.csv.zst")
  if (is.na(trips_file)) {
    stop("Missing output_trips.csv.zst in final run: ", paths$final_run_dir, call. = FALSE)
  }

  trips <- safe_read_delim_auto(trips_file)
  if (nrow(trips) == 0) {
    stop("output_trips.csv.zst is empty: ", trips_file, call. = FALSE)
  }

  required <- c("person", "trip_id", "trav_time", "traveled_distance", "main_mode")
  missing <- setdiff(required, names(trips))
  if (length(missing) > 0) {
    stop("Trips file misses required columns: ", paste(missing, collapse = ", "), call. = FALSE)
  }

  person_lookup <- build_person_lookup(
    person_scores,
    c("person", "subpopulation", "goods_type", "carrier_id", "tour_id", "vehicle_types_raw")
  )

  trips %>%
    transmute(
      person = as.character(.data$person),
      trip_id = as.character(.data$trip_id),
      main_mode = as.character(.data$main_mode),
      traveled_distance_m = as_number(.data$traveled_distance),
      traveled_distance_km = traveled_distance_m / 1000,
      travel_time_s = parse_time_to_seconds(.data$trav_time),
      travel_time_h = travel_time_s / 3600
    ) %>%
    left_join(person_lookup, by = "person") %>%
    mutate(subpopulation = clean_subpopulation(subpopulation)) %>%
    with_meta(paths, basename(trips_file))
}

# Reads selected-plan distances and times from the MATSim plans file.
read_selected_plan_measures <- function(paths, person_scores) {
  plan_sources <- find_selected_plan_source_files(paths)
  if (nrow(plan_sources) == 0) {
    add_warning("Missing selected-plan XML source for longDistanceFreight/FTL in: ", paths$sample_dir)
    return(tibble())
  }

  selected_plan_chunks <- list()
  boundary_person_filter <- character()
  long_distance_person_filter <- build_long_distance_person_filter(person_scores)

  for (source_index in seq_len(nrow(plan_sources))) {
    source_component_hint <- plan_sources$source_component_hint[[source_index]]
    plans_file <- plan_sources$plans_file[[source_index]]
    source_rank <- plan_sources$source_rank[[source_index]]
    source_role <- plan_sources$source_role[[source_index]]
    route_filter <- if (length(boundary_person_filter) > 0) boundary_person_filter else long_distance_person_filter
    person_filter <- if (source_role == "cutout_route") route_filter else NULL

    parsed <- tryCatch(
      parse_selected_plan_measures(plans_file, person_filter),
      error = function(e) {
        add_warning("Could not parse selected-plan source ", plans_file, ": ", conditionMessage(e))
        empty_selected_plan_measures()
      }
    )

    parsed <- parsed %>%
      mutate(
        source_component_hint = source_component_hint,
        selected_plan_source_file = normalizePath(plans_file, winslash = "/", mustWork = FALSE),
        source_rank = source_rank,
        source_role = source_role,
        selected_plan_boundary_distance_m = ifelse(source_role == "cutout_route", 0, selected_plan_boundary_distance_m),
        selected_plan_boundary_travel_time_s = ifelse(source_role == "cutout_route", 0, selected_plan_boundary_travel_time_s),
        selected_plan_distance_m = selected_plan_route_distance_m + selected_plan_boundary_distance_m,
        selected_plan_travel_time_s = selected_plan_route_travel_time_s + selected_plan_boundary_travel_time_s
      ) %>%
      with_meta(paths, basename(plans_file))
    selected_plan_chunks[[source_index]] <- parsed

    if (source_role == "boundary" && nrow(parsed) > 0) {
      boundary_person_filter <- unique(c(boundary_person_filter, parsed$person))
    }
  }

  selected_plans <- bind_rows(selected_plan_chunks)

  if (nrow(selected_plans) == 0) {
    add_warning(
      "No selected plans with route distance/time found in selected-plan source(s): ",
      paste(plan_sources$plans_file, collapse = ";")
    )
    return(tibble())
  }

  person_lookup <- build_person_lookup(
    person_scores,
    c("person", "subpopulation", "goods_type", "carrier_id", "tour_id", "vehicle_types_raw")
  )
  component_lookup <- selected_plans %>%
    filter(source_role == "boundary", !is.na(source_component_hint)) %>%
    group_by(person) %>%
    summarise(
      boundary_component = ifelse(n_distinct(source_component_hint) == 1, first(source_component_hint), NA_character_),
      .groups = "drop"
    )

  selected_plans %>%
    left_join(person_lookup, by = "person") %>%
    left_join(component_lookup, by = "person") %>%
    mutate(
      subpopulation = clean_subpopulation(subpopulation),
      selected_plan_route_distance_km = selected_plan_route_distance_m / 1000,
      selected_plan_route_travel_time_h = selected_plan_route_travel_time_s / 3600,
      selected_plan_boundary_distance_km = selected_plan_boundary_distance_m / 1000,
      selected_plan_boundary_travel_time_h = selected_plan_boundary_travel_time_s / 3600,
      selected_plan_distance_km = selected_plan_distance_m / 1000,
      selected_plan_travel_time_h = selected_plan_travel_time_s / 3600,
      paper_component = coalesce(
        boundary_component,
        source_component_hint,
        paper_component_from_trip(subpopulation, goods_type),
        paper_component_from_freight_person_id(person)
      )
    )
}

# Builds the optional route-source person filter for longDistanceFreight and FTL.
build_long_distance_person_filter <- function(person_scores) {
  if (nrow(person_scores) == 0) {
    return(NULL)
  }

  selected <- person_scores %>%
    mutate(
      paper_component = coalesce(
        paper_component_from_trip(subpopulation, goods_type),
        paper_component_from_freight_person_id(person)
      )
    ) %>%
    filter(paper_component %in% c("FTL", "longDistanceFreight")) %>%
    pull(person) %>%
    unique()

  if (length(selected) == 0) NULL else selected
}

# Finds component-specific long-distance plan files and the final-run selected plans.
find_selected_plan_source_files <- function(paths) {
  component_sources <- tibble(
    source_component_hint = c("longDistanceFreight", "FTL"),
    plans_file = c(
      file.path(paths$sample_dir, paste0("ruhr_longDistanceFreight.", paths$sample_tag, "pct.plans.xml.gz")),
      file.path(paths$sample_dir, paste0("ruhr_FTL_freightPlans_", paths$sample_tag, "pct.plans.xml.gz"))
    ),
    source_rank = 1L,
    source_role = "boundary"
  ) %>%
    filter(file.exists(plans_file))

  route_source <- find_run_file(paths, ".output_plans.xml.zst")
  route_source_row <- if (is.na(route_source)) {
    tibble(source_component_hint = character(), plans_file = character(), source_rank = integer(), source_role = character())
  } else {
    tibble(source_component_hint = NA_character_, plans_file = route_source, source_rank = 2L, source_role = "cutout_route")
  }

  if (nrow(component_sources) > 0) {
    return(bind_rows(component_sources, route_source_row))
  }

  route_source_row
}

# Reads selected-plan legs and score-relevant activities in one streaming pass for cost reconstruction.
read_selected_plan_cost_inputs <- function(paths, person_scores) {
  plan_sources <- find_selected_plan_source_files(paths)
  if (nrow(plan_sources) == 0) {
    add_warning("Missing selected-plan XML source for cost reconstruction in: ", paths$sample_dir)
    return(list(legs = tibble(), activities = tibble()))
  }

  leg_chunks <- list()
  activity_chunks <- list()
  parsed_chunks <- list()
  for (source_index in seq_len(nrow(plan_sources))) {
    source_component_hint <- plan_sources$source_component_hint[[source_index]]
    plans_file <- plan_sources$plans_file[[source_index]]
    source_role <- plan_sources$source_role[[source_index]]

    parsed <- tryCatch(
      parse_selected_plan_cost_inputs(plans_file),
      error = function(e) {
        add_warning("Could not parse selected-plan cost inputs from ", plans_file, ": ", conditionMessage(e))
        list(legs = empty_selected_plan_cost_legs(), activities = empty_selected_plan_cost_activities())
      }
    )

    parsed_chunks[[source_index]] <- list(
      parsed = parsed,
      source_component_hint = source_component_hint,
      source_role = source_role
    )
  }

  source_component_lookup <- purrr::map_dfr(parsed_chunks, function(source) {
    if (isTRUE(source$source_role != "boundary") || is.na(source$source_component_hint)) {
      return(tibble(person = character(), source_component_from_boundary_plan = character()))
    }

    parsed_persons <- bind_rows(
      source$parsed$legs %>% select(person),
      source$parsed$activities %>% select(person)
    ) %>%
      distinct(person) %>%
      filter(!is.na(person), nzchar(person))

    if (nrow(parsed_persons) == 0) {
      return(tibble(person = character(), source_component_from_boundary_plan = character()))
    }

    parsed_persons %>%
      transmute(
        person,
        source_component_from_boundary_plan = source$source_component_hint
      )
  }) %>%
    group_by(person) %>%
    summarise(
      source_component_from_boundary_plan = ifelse(
        n_distinct(source_component_from_boundary_plan) == 1,
        first(source_component_from_boundary_plan),
        NA_character_
      ),
      .groups = "drop"
    )

  for (source_index in seq_len(nrow(plan_sources))) {
    source_component_hint <- plan_sources$source_component_hint[[source_index]]
    plans_file <- plan_sources$plans_file[[source_index]]
    source_role <- plan_sources$source_role[[source_index]]
    parsed <- parsed_chunks[[source_index]]$parsed

    leg_chunks[[source_index]] <- if (nrow(parsed$legs) == 0) {
      if (source_role == "cutout_route") {
        add_warning("No selected-plan route legs found for cost reconstruction in: ", plans_file)
      }
      tibble()
    } else {
      decorate_selected_plan_cost_legs(
        parsed$legs,
        plans_file,
        person_scores,
        paths,
        source_component_hint,
        source_role,
        source_component_lookup
      )
    }
    activity_chunks[[source_index]] <- decorate_selected_plan_cost_activities(
      parsed$activities,
      plans_file,
      person_scores,
      paths,
      source_component_hint,
      source_role,
      source_component_lookup
    )
  }

  list(
    legs = bind_rows(leg_chunks),
    activities = bind_rows(activity_chunks)
  )
}

# Streams selected plans and emits both routed legs and score-relevant activity durations.
parse_selected_plan_cost_inputs <- function(plans_file, person_filter = NULL) {
  parsed_file <- materialize_zst_text_file(plans_file)
  if (!identical(parsed_file, plans_file)) {
    on.exit(unlink(parsed_file), add = TRUE)
  }

  con <- open_text_connection(parsed_file)
  on.exit(close(con), add = TRUE)

  person_filter_env <- build_person_filter_env(person_filter)
  current_person <- NA_character_
  current_person_matches_filter <- TRUE
  current_vehicle_types_raw <- NA_character_
  current_vehicle_type_values <- NA_character_
  inside_selected_plan <- FALSE
  current_leg_mode <- NA_character_
  current_leg_index <- 0L
  current_activity_index <- 0L
  current_leg_travel_time_s <- NA_real_
  parsed_leg_rows <- list()
  parsed_activity_rows <- list()
  leg_row_index <- 0L
  activity_row_index <- 0L

  repeat {
    lines <- readLines(con, n = 100000, warn = FALSE)
    if (length(lines) == 0) {
      break
    }

    person_start_lines <- grepl("<person ", lines, fixed = TRUE)
    person_end_lines <- grepl("</person>", lines, fixed = TRUE)
    vehicle_type_lines <- grepl('attribute name="vehicleTypes"', lines, fixed = TRUE)
    plan_lines <- grepl("<plan ", lines, fixed = TRUE)
    activity_lines <- grepl("<activity ", lines, fixed = TRUE)
    leg_lines <- grepl("<leg ", lines, fixed = TRUE)
    route_lines <- grepl("<route ", lines, fixed = TRUE)
    plan_end_lines <- grepl("</plan>", lines, fixed = TRUE)
    relevant_lines <- which(
      person_start_lines | person_end_lines | vehicle_type_lines |
        plan_lines | activity_lines | leg_lines | route_lines | plan_end_lines
    )

    for (line_index in relevant_lines) {
      line <- lines[[line_index]]

      if (person_start_lines[[line_index]]) {
        current_person <- xml_attr(line, "id")
        current_person_matches_filter <- matches_person_filter(current_person, person_filter_env)
        current_vehicle_types_raw <- NA_character_
        current_vehicle_type_values <- NA_character_
      }

      if (vehicle_type_lines[[line_index]] && !is.na(current_person)) {
        current_vehicle_types_raw <- xml_attribute_text(line)
        current_vehicle_type_values <- extract_vehicle_type_values(current_vehicle_types_raw)
      }

      if (plan_lines[[line_index]]) {
        selected_attr <- tolower(ifelse(is.na(xml_attr(line, "selected")), "", xml_attr(line, "selected")))
        inside_selected_plan <- current_person_matches_filter && selected_attr %in% c("", "yes")
        current_leg_mode <- NA_character_
        current_leg_index <- 0L
        current_activity_index <- 0L
        current_leg_travel_time_s <- NA_real_
      }

      if (inside_selected_plan && activity_lines[[line_index]]) {
        current_activity_index <- current_activity_index + 1L
        activity_duration_s <- parse_activity_duration_to_seconds(line)
        activity_row_index <- activity_row_index + 1L
        parsed_activity_rows[[activity_row_index]] <- tibble(
          person = current_person,
          activity_index = current_activity_index,
          activity_type = xml_attr(line, "type"),
          activity_duration_s = ifelse(is.na(activity_duration_s), 0, activity_duration_s),
          activity_leg_mode_hint = current_leg_mode,
          plan_vehicle_types_raw = current_vehicle_types_raw,
          plan_vehicle_type_values = current_vehicle_type_values
        )
      }

      if (inside_selected_plan && leg_lines[[line_index]]) {
        current_leg_index <- current_leg_index + 1L
        current_leg_mode <- xml_attr(line, "mode")
        current_leg_travel_time_s <- parse_time_to_seconds(xml_attr(line, "trav_time"))
      }

      if (inside_selected_plan && route_lines[[line_index]]) {
        route_distance_m <- as_number(xml_attr(line, "distance"))
        route_travel_time_s <- parse_time_to_seconds(xml_attr(line, "trav_time"))
        if (is.na(route_travel_time_s)) {
          route_travel_time_s <- current_leg_travel_time_s
        }

        leg_row_index <- leg_row_index + 1L
        parsed_leg_rows[[leg_row_index]] <- tibble(
          person = current_person,
          leg_index = current_leg_index,
          leg_mode = current_leg_mode,
          route_distance_m = ifelse(is.na(route_distance_m), 0, route_distance_m),
          route_travel_time_s = ifelse(is.na(route_travel_time_s), 0, route_travel_time_s),
          plan_vehicle_types_raw = current_vehicle_types_raw,
          plan_vehicle_type_values = current_vehicle_type_values
        )

        current_leg_travel_time_s <- NA_real_
      }

      if (inside_selected_plan && plan_end_lines[[line_index]]) {
        inside_selected_plan <- FALSE
      }

      if (person_end_lines[[line_index]]) {
        current_person <- NA_character_
        current_person_matches_filter <- TRUE
        current_vehicle_types_raw <- NA_character_
        current_vehicle_type_values <- NA_character_
      }
    }
  }

  list(
    legs = if (length(parsed_leg_rows) == 0) empty_selected_plan_cost_legs() else bind_rows(parsed_leg_rows),
    activities = if (length(parsed_activity_rows) == 0) empty_selected_plan_cost_activities() else bind_rows(parsed_activity_rows)
  )
}

# Adds person metadata and resolved vehicle types to selected-plan leg rows.
decorate_selected_plan_cost_legs <- function(parsed, plans_file, person_scores, paths,
                                             source_component_hint = NA_character_,
                                             source_role = NA_character_,
                                             source_component_lookup = tibble(
                                               person = character(),
                                               source_component_from_boundary_plan = character()
                                             )) {
  if (nrow(parsed) == 0) {
    add_warning("No selected-plan route legs found for cost reconstruction in: ", plans_file)
    return(tibble())
  }

  person_lookup <- build_person_lookup(
    person_scores,
    c("person", "subpopulation", "goods_type", "carrier_id", "tour_id", "vehicle_types_raw")
  )

  parsed %>%
    left_join(person_lookup, by = "person") %>%
    left_join(source_component_lookup, by = "person") %>%
    mutate(
      vehicle_types_raw = ifelse(
        !is.na(plan_vehicle_types_raw) & nzchar(plan_vehicle_types_raw),
        plan_vehicle_types_raw,
        vehicle_types_raw
      ),
      vehicle_type_values = ifelse(
        !is.na(plan_vehicle_type_values) & nzchar(plan_vehicle_type_values),
        plan_vehicle_type_values,
        vapply(vehicle_types_raw, extract_vehicle_type_values, character(1), USE.NAMES = FALSE)
      ),
      subpopulation = clean_subpopulation(subpopulation),
      leg_vehicle_type = mapply(
        resolve_vehicle_type_for_leg,
        leg_mode,
        vehicle_types_raw,
        vehicle_type_values,
        USE.NAMES = FALSE
      ),
      source_component_hint = source_component_hint,
      source_role = source_role,
      selected_plan_source_file = normalizePath(plans_file, winslash = "/", mustWork = FALSE),
      paper_component = coalesce(
        source_component_hint,
        source_component_from_boundary_plan,
        paper_component_from_ltl_tour_person(person, leg_vehicle_type),
        paper_component_from_cost_person(subpopulation, goods_type),
        paper_component_from_freight_person_id(person)
      ),
      route_distance_km = route_distance_m / 1000,
      route_travel_time_h = route_travel_time_s / 3600
    ) %>%
    select(
      person, leg_index, leg_mode, leg_vehicle_type,
      route_distance_m, route_travel_time_s, route_distance_km, route_travel_time_h,
      vehicle_types_raw, vehicle_type_values, subpopulation, goods_type, carrier_id, tour_id,
      paper_component, source_component_hint, source_role, selected_plan_source_file
    ) %>%
    with_meta(paths, basename(plans_file))
}

# Adds person metadata and resolved vehicle types to selected-plan activity rows.
decorate_selected_plan_cost_activities <- function(parsed, plans_file, person_scores, paths,
                                                  source_component_hint = NA_character_,
                                                  source_role = NA_character_,
                                                  source_component_lookup = tibble(
                                                    person = character(),
                                                    source_component_from_boundary_plan = character()
                                                  )) {
  if (nrow(parsed) == 0) {
    return(tibble())
  }

  person_lookup <- build_person_lookup(
    person_scores,
    c("person", "subpopulation", "goods_type", "carrier_id", "tour_id", "vehicle_types_raw")
  )

  parsed %>%
    left_join(person_lookup, by = "person") %>%
    left_join(source_component_lookup, by = "person") %>%
    mutate(
      vehicle_types_raw = ifelse(
        !is.na(plan_vehicle_types_raw) & nzchar(plan_vehicle_types_raw),
        plan_vehicle_types_raw,
        vehicle_types_raw
      ),
      vehicle_type_values = ifelse(
        !is.na(plan_vehicle_type_values) & nzchar(plan_vehicle_type_values),
        plan_vehicle_type_values,
        vapply(vehicle_types_raw, extract_vehicle_type_values, character(1), USE.NAMES = FALSE)
      ),
      subpopulation = clean_subpopulation(subpopulation),
      activity_vehicle_type = mapply(
        resolve_vehicle_type_for_leg,
        activity_leg_mode_hint,
        vehicle_types_raw,
        vehicle_type_values,
        USE.NAMES = FALSE
      ),
      source_component_hint = source_component_hint,
      source_role = source_role,
      selected_plan_source_file = normalizePath(plans_file, winslash = "/", mustWork = FALSE),
      paper_component = coalesce(
        source_component_hint,
        source_component_from_boundary_plan,
        paper_component_from_ltl_tour_person(person, activity_vehicle_type),
        paper_component_from_cost_person(subpopulation, goods_type),
        paper_component_from_freight_person_id(person)
      ),
      activity_duration_h = activity_duration_s / 3600
    ) %>%
    filter(is_score_relevant_cost_activity(activity_type), activity_duration_s > 0) %>%
    select(
      person, activity_index, activity_type, activity_duration_s, activity_duration_h,
      activity_leg_mode_hint, activity_vehicle_type,
      vehicle_types_raw, vehicle_type_values, subpopulation, goods_type, carrier_id, tour_id,
      paper_component, source_component_hint, source_role, selected_plan_source_file
    ) %>%
    with_meta(paths, basename(plans_file))
}

# Provides the stable leg-level selected-plan parser schema for empty or unreadable XML sources.
empty_selected_plan_cost_legs <- function() {
  tibble(
    person = character(),
    leg_index = integer(),
    leg_mode = character(),
    route_distance_m = numeric(),
    route_travel_time_s = numeric(),
    plan_vehicle_types_raw = character(),
    plan_vehicle_type_values = character()
  )
}

# Provides the selected-plan activity parser schema for empty or unreadable XML sources.
empty_selected_plan_cost_activities <- function() {
  tibble(
    person = character(),
    activity_index = integer(),
    activity_type = character(),
    activity_duration_s = numeric(),
    activity_leg_mode_hint = character(),
    plan_vehicle_types_raw = character(),
    plan_vehicle_type_values = character()
  )
}

# Parses MATSim activity duration attributes in seconds.
parse_activity_duration_to_seconds <- function(line) {
  duration <- coalesce(
    parse_time_to_seconds(xml_attr(line, "max_dur")),
    parse_time_to_seconds(xml_attr(line, "duration"))
  )
  if (!is.na(duration)) {
    return(duration)
  }

  start_time <- parse_time_to_seconds(xml_attr(line, "start_time"))
  end_time <- parse_time_to_seconds(xml_attr(line, "end_time"))
  if (!is.na(start_time) && !is.na(end_time) && end_time >= start_time) {
    return(end_time - start_time)
  }

  NA_real_
}

# Identifies selected-plan activities that are scored as operator-side service/handling time costs.
is_score_relevant_cost_activity <- function(activity_type) {
  activity_type <- as.character(activity_type)
  !is.na(activity_type) &
    nzchar(activity_type) &
    !activity_type %in% c("commercial_start", "commercial_end", "freight_start", "freight_end") &
    !grepl(" interaction$", activity_type) &
    !grepl("_start$", activity_type) &
    !grepl("_end$", activity_type)
}

# Streams the plans XML so large MATSim output files do not need to be held in memory.
parse_selected_plan_measures <- function(plans_file, person_filter = NULL) {
  parsed_file <- materialize_zst_text_file(plans_file)
  if (!identical(parsed_file, plans_file)) {
    on.exit(unlink(parsed_file), add = TRUE)
  }

  con <- open_text_connection(parsed_file)
  on.exit(close(con), add = TRUE)

  person_filter_env <- build_person_filter_env(person_filter)
  current_person <- NA_character_
  current_person_matches_filter <- TRUE
  inside_selected_plan <- FALSE
  current_route_distance_m <- 0
  current_route_travel_time_s <- 0
  current_boundary_distance_m <- 0
  current_boundary_travel_time_s <- 0
  current_leg_travel_time_s <- NA_real_
  parsed_rows <- list()
  row_index <- 0L

  repeat {
    lines <- readLines(con, n = 100000, warn = FALSE)
    if (length(lines) == 0) {
      break
    }

    person_start_lines <- grepl("<person ", lines, fixed = TRUE)
    person_end_lines <- grepl("</person>", lines, fixed = TRUE)
    plan_lines <- grepl("<plan ", lines, fixed = TRUE)
    leg_lines <- grepl("<leg ", lines, fixed = TRUE)
    route_lines <- grepl("<route ", lines, fixed = TRUE)
    boundary_distance_lines <- grepl('name="routedDistance', lines, fixed = TRUE)
    boundary_time_lines <- grepl('name="routedTravelTime', lines, fixed = TRUE)
    plan_end_lines <- grepl("</plan>", lines, fixed = TRUE)
    relevant_lines <- which(
      person_start_lines | person_end_lines | plan_lines | leg_lines | route_lines |
        boundary_distance_lines | boundary_time_lines | plan_end_lines
    )

    for (line_index in relevant_lines) {
      line <- lines[[line_index]]

      if (person_start_lines[[line_index]]) {
        current_person <- xml_attr(line, "id")
        current_person_matches_filter <- matches_person_filter(current_person, person_filter_env)
      }

      if (plan_lines[[line_index]]) {
        selected_attr <- tolower(ifelse(is.na(xml_attr(line, "selected")), "", xml_attr(line, "selected")))
        inside_selected_plan <- current_person_matches_filter && selected_attr %in% c("", "yes")
        current_route_distance_m <- 0
        current_route_travel_time_s <- 0
        current_boundary_distance_m <- 0
        current_boundary_travel_time_s <- 0
        current_leg_travel_time_s <- NA_real_
      }

      if (inside_selected_plan && leg_lines[[line_index]]) {
        current_leg_travel_time_s <- parse_time_to_seconds(xml_attr(line, "trav_time"))
      }

      if (inside_selected_plan && route_lines[[line_index]]) {
        route_distance_m <- as_number(xml_attr(line, "distance"))
        route_travel_time_s <- parse_time_to_seconds(xml_attr(line, "trav_time"))

        if (is.na(route_travel_time_s)) {
          route_travel_time_s <- current_leg_travel_time_s
        }

        current_route_distance_m <- current_route_distance_m + ifelse(is.na(route_distance_m), 0, route_distance_m)
        current_route_travel_time_s <- current_route_travel_time_s + ifelse(is.na(route_travel_time_s), 0, route_travel_time_s)
        current_leg_travel_time_s <- NA_real_
      }

      if (inside_selected_plan && boundary_distance_lines[[line_index]]) {
        boundary_distance_m <- as_number(xml_attribute_text(line))
        current_boundary_distance_m <- current_boundary_distance_m + ifelse(is.na(boundary_distance_m), 0, boundary_distance_m)
      }

      if (inside_selected_plan && boundary_time_lines[[line_index]]) {
        boundary_travel_time_s <- as_number(xml_attribute_text(line))
        current_boundary_travel_time_s <- current_boundary_travel_time_s + ifelse(is.na(boundary_travel_time_s), 0, boundary_travel_time_s)
      }

      if (inside_selected_plan && plan_end_lines[[line_index]]) {
        row_index <- row_index + 1L
        parsed_rows[[row_index]] <- tibble(
          person = current_person,
          selected_plan_route_distance_m = current_route_distance_m,
          selected_plan_route_travel_time_s = current_route_travel_time_s,
          selected_plan_boundary_distance_m = current_boundary_distance_m,
          selected_plan_boundary_travel_time_s = current_boundary_travel_time_s,
          selected_plan_distance_m = current_route_distance_m + current_boundary_distance_m,
          selected_plan_travel_time_s = current_route_travel_time_s + current_boundary_travel_time_s
        )
        inside_selected_plan <- FALSE
      }

      if (person_end_lines[[line_index]]) {
        current_person <- NA_character_
        current_person_matches_filter <- TRUE
      }
    }
  }

  if (length(parsed_rows) == 0) {
    return(empty_selected_plan_measures())
  }

  bind_rows(parsed_rows)
}

# Decompresses .zst XML to a temporary file because R pipe streaming is too slow for large plans.
materialize_zst_text_file <- function(path) {
  if (!grepl("\\.zst$", path, ignore.case = TRUE)) {
    return(path)
  }

  extractor <- zstd_extractor(path)
  if (is.null(extractor)) {
    stop(
      "Cannot read Zstandard-compressed file without zstd, unzstd or 7-Zip: ",
      path,
      call. = FALSE
    )
  }

  tmp <- tempfile(pattern = "plans_", fileext = ".xml")
  status <- system2(extractor$command, extractor$args, stdout = tmp, stderr = FALSE)
  if (!identical(status, 0L)) {
    unlink(tmp)
    stop("Could not decompress Zstandard-compressed file: ", path, call. = FALSE)
  }

  tmp
}

# Locates an installed Zstandard-capable extractor and returns command plus arguments.
zstd_extractor <- function(path) {
  zstd <- Sys.which("zstd")
  if (!is.na(zstd) && nzchar(zstd)) {
    return(list(command = normalizePath(zstd, winslash = "/", mustWork = FALSE), args = c("-dc", path)))
  }

  unzstd <- Sys.which("unzstd")
  if (!is.na(unzstd) && nzchar(unzstd)) {
    return(list(command = normalizePath(unzstd, winslash = "/", mustWork = FALSE), args = c("-c", path)))
  }

  seven_zip_candidates <- c(
    Sys.which("7z"),
    "C:/Program Files/7-Zip/7z.exe",
    "C:/Program Files (x86)/7-Zip/7z.exe"
  )
  seven_zip <- seven_zip_candidates[nzchar(seven_zip_candidates) & file.exists(seven_zip_candidates)]
  if (length(seven_zip) > 0) {
    return(list(command = normalizePath(seven_zip[[1]], winslash = "/", mustWork = TRUE), args = c("x", "-so", path)))
  }

  NULL
}

# Builds a hash environment for fast person-id filtering while streaming large plans files.
build_person_filter_env <- function(person_filter) {
  person_filter <- unique(na.omit(as.character(person_filter)))
  if (length(person_filter) == 0) {
    return(NULL)
  }

  list2env(stats::setNames(as.list(rep(TRUE, length(person_filter))), person_filter), parent = emptyenv())
}

# Checks whether the current streamed person should be parsed.
matches_person_filter <- function(person, person_filter_env) {
  is.null(person_filter_env) || (!is.na(person) && exists(person, envir = person_filter_env, inherits = FALSE))
}

# Provides the stable selected-plan parser schema for empty or unreadable XML sources.
empty_selected_plan_measures <- function() {
  tibble(
    person = character(),
    selected_plan_route_distance_m = numeric(),
    selected_plan_route_travel_time_s = numeric(),
    selected_plan_boundary_distance_m = numeric(),
    selected_plan_boundary_travel_time_s = numeric(),
    selected_plan_distance_m = numeric(),
    selected_plan_travel_time_s = numeric()
  )
}

# Extracts a simple XML attribute value from one line.
xml_attr <- function(line, attr) {
  match <- str_match(line, paste0(attr, '="([^"]*)"'))[, 2]
  ifelse(is.na(match), NA_character_, match)
}

# Extracts the text value from a single-line MATSim XML attribute element.
xml_attribute_text <- function(line) {
  match <- str_match(line, "<attribute[^>]*>([^<]*)</attribute>")[, 2]
  ifelse(is.na(match), NA_character_, match)
}

# Maps tour-analysis groups to the paper components that are evaluated by study-area-start scope.
paper_component_from_tour_group <- function(group) {
  normalized <- clean_subpopulation(group)

  case_when(
    normalized == "commercialPersonTraffic" ~ "commercialPersonTraffic",
    normalized %in% c("goodsTraffic", "smallScaleGoodsTraffic") ~ "smallScaleGoodsTraffic",
    TRUE ~ NA_character_
  )
}

# Maps trip/person rows to the paper components that are evaluated for all generated agents.
paper_component_from_trip <- function(subpopulation, goods_type = NA_real_) {
  normalized <- clean_subpopulation(subpopulation)
  goods_type <- as_number(goods_type)
  is_ltl <- normalized %in% c("LTL", "LTL_trip")

  case_when(
    normalized %in% c("FTL", "FTL_trip", "FTL_kv_trip") ~ "FTL",
    normalized %in% c("longDistanceFreight", "longDistanceFreight_trip") ~ "longDistanceFreight",
    is_ltl & goods_type == 150 ~ "CEP",
    is_ltl ~ "remainingLTL",
    TRUE ~ NA_character_
  )
}

paper_component_from_ltl_tour_person <- function(person_id, vehicle_type = NA_character_) {
  person_text <- as.character(person_id)
  person_text[is.na(person_text)] <- ""
  vehicle_type_base <- normalize_vehicle_type(vehicle_type)
  vehicle_type_base[is.na(vehicle_type_base)] <- ""

  case_when(
    grepl("^ParcelDelivery", person_text, ignore.case = TRUE) ~ "CEP",
    grepl("^WasteCollection", person_text, ignore.case = TRUE) |
      grepl("^waste[_]?collection", person_text, ignore.case = TRUE) |
      grepl("^waste[_]?collection", vehicle_type_base, ignore.case = TRUE) ~ "wasteCollection",
    grepl("^GoodsType_", person_text, ignore.case = TRUE) |
      grepl("^LTL", person_text, ignore.case = TRUE) ~ "remainingLTL",
    TRUE ~ NA_character_
  )
}

paper_component_from_freight_person_id <- function(person_id) {
  person_text <- as.character(person_id)
  person_text[is.na(person_text)] <- ""

  case_when(
    grepl("_FTL($|_return$)", person_text, ignore.case = TRUE) ~ "FTL",
    grepl("^freight_.*_main$", person_text, ignore.case = TRUE) ~ "longDistanceFreight",
    TRUE ~ NA_character_
  )
}

# Maps person rows to the component scope used by cost reconstruction.
paper_component_from_cost_person <- function(subpopulation, goods_type = NA_real_) {
  normalized <- clean_subpopulation(subpopulation)
  trip_component <- paper_component_from_trip(normalized, goods_type)

  case_when(
    normalized == "commercialPersonTraffic" ~ "commercialPersonTraffic",
    normalized %in% c("goodsTraffic", "smallScaleGoodsTraffic") ~ "smallScaleGoodsTraffic",
    TRUE ~ trip_component
  )
}

# Assigns a stable row group used by the paper CSVs.
component_group_from_paper_component <- function(component) {
  case_when(
    component %in% c("wasteCollection", "CEP", "remainingLTL") ~ "ltl_detail",
    component %in% c("LTL", "small-scale goods total", "long-distance total", "all components", "comparable cost total") ~ "total",
    TRUE ~ "model_component"
  )
}

# Builds the main paper key facts while keeping the component-specific inclusion rules explicit.
build_paper_key_facts_by_component <- function(tour_distances, tour_durations, trip_measures, person_scores,
                                               selected_plan_measures, paper_costs_by_component, paths) {
  small_components <- build_small_component_key_facts(tour_distances, trip_measures, paths)
  trip_components <- build_trip_component_key_facts(trip_measures, person_scores, paths)
  ltl_agent_components <- build_ltl_component_key_facts(trip_components, paths)
  trip_components <- trip_components %>%
    filter(!component %in% ltl_components())
  selected_long_distance <- build_selected_long_distance_key_facts(selected_plan_measures, trip_components, paths)
  long_distance_components <- c("FTL", "longDistanceFreight")

  if (nrow(selected_long_distance) > 0) {
    selected_long_distance_components <- unique(selected_long_distance$component)
    trip_long_distance_components <- unique(trip_components$component[trip_components$component %in% long_distance_components])
    missing_selected_components <- setdiff(trip_long_distance_components, selected_long_distance_components)
    trip_components <- trip_components %>%
      filter(!component %in% selected_long_distance_components)
    if (length(missing_selected_components) > 0) {
      add_warning(
        "No selected-plan long-distance values available for ",
        paste(missing_selected_components, collapse = ", "),
        "; paper_key_facts_by_component keeps represented cutout values from output_trips.csv for these components."
      )
    }
  } else if (any(trip_components$component %in% long_distance_components)) {
    add_warning(
      "No selected-plan longDistanceFreight/FTL values available; paper_key_facts_by_component uses ",
      "represented cutout values from output_trips.csv for these components."
    )
  }

  rows <- bind_rows(small_components, ltl_agent_components, trip_components, selected_long_distance)
  if (nrow(rows) == 0) {
    return(tibble())
  }
  rows <- apply_reconstructed_costs_to_key_facts(rows, paper_costs_by_component, paths)
  rows <- attach_tour_durations_to_key_facts(rows, tour_durations, tour_distances, person_scores, paths)
  rows <- add_simulation_boundary_key_fact_columns(rows)

  rows %>%
    mutate(
      component_group = component_group_from_paper_component(component),
      avg_distance_km_inSimulation_per_vehicle = safe_divide(distance_km_inSimulation, vehicles),
      avg_distance_km_withBoundary_per_vehicle = safe_divide(distance_km_withBoundary, vehicles),
      avg_travel_time_h_inSimulation_per_vehicle = safe_divide(travel_time_h_inSimulation, vehicles),
      avg_travel_time_h_withBoundary_per_vehicle = safe_divide(travel_time_h_withBoundary, vehicles),
      avg_tour_duration_h_inSimulation_per_vehicle = safe_divide(tour_duration_h_inSimulation, vehicles),
      avg_tour_duration_h_withBoundary_per_vehicle = safe_divide(tour_duration_h_withBoundary, vehicles),
      avg_distance_km_per_vehicle = avg_distance_km_withBoundary_per_vehicle,
      avg_travel_time_h_per_vehicle = avg_travel_time_h_withBoundary_per_vehicle,
      avg_tour_duration_h_per_vehicle = avg_tour_duration_h_withBoundary_per_vehicle
    ) %>%
    arrange(match(component, c(
      "commercialPersonTraffic", "smallScaleGoodsTraffic", "wasteCollection",
      "CEP", "remainingLTL", "FTL", "longDistanceFreight"
    ))) %>%
    relocate(
      scenario_name, year, fleet_type, model_type, sample_pct, sample_factor_to_100pct,
      final_run_dir, component_group, component, paper_relevance, sample_scope,
      evaluation_scope, metric_scope, source_dataset, inclusion_rule
    )
}

# Adds the R-reconstructed selected-plan costs to the paper key facts for the run year.
apply_reconstructed_costs_to_key_facts <- function(rows, paper_costs_by_component, paths) {
  if (nrow(rows) == 0 || is.null(paper_costs_by_component) || nrow(paper_costs_by_component) == 0) {
    return(rows)
  }

  cost_rows <- paper_costs_by_component %>%
    filter(row_type == "component", as.integer(cost_result_year) == as.integer(paths$year)) %>%
    transmute(
      component,
      reconstructed_total_cost_eur = total_cost_eur,
      reconstructed_total_cost_eur_inSimulation =
        total_cost_eur - coalesce(boundary_distance_cost_eur, 0) - coalesce(boundary_time_cost_eur, 0),
      reconstructed_total_cost_eur_withBoundary = total_cost_eur,
      reconstructed_source_dataset = source_dataset,
      reconstructed_source_file = source_file
    )
  if (nrow(cost_rows) == 0) {
    return(rows)
  }

  rows %>%
    left_join(cost_rows, by = "component") %>%
    mutate(
      has_reconstructed_cost = !is.na(reconstructed_total_cost_eur),
      total_cost_eur_inSimulation = ifelse(
        has_reconstructed_cost,
        reconstructed_total_cost_eur_inSimulation,
        total_cost_eur
      ),
      total_cost_eur_withBoundary = ifelse(
        has_reconstructed_cost,
        reconstructed_total_cost_eur_withBoundary,
        total_cost_eur
      ),
      total_cost_eur = ifelse(has_reconstructed_cost, reconstructed_total_cost_eur, total_cost_eur),
      source_dataset = ifelse(
        has_reconstructed_cost,
        paste_source_columns(source_dataset, reconstructed_source_dataset),
        source_dataset
      ),
      source_file = paste_source_columns(source_file, reconstructed_source_file)
    ) %>%
    select(
      -reconstructed_total_cost_eur, -reconstructed_total_cost_eur_inSimulation,
      -reconstructed_total_cost_eur_withBoundary,
      -reconstructed_source_dataset, -reconstructed_source_file, -has_reconstructed_cost
    )
}

# Adds explicit simulation-only and boundary-augmented metric columns for paper exports.
add_simulation_boundary_key_fact_columns <- function(rows) {
  if (nrow(rows) == 0) {
    return(rows)
  }

  optional_cols <- c(
    "route_distance_km", "cutout_distance_km", "boundary_distance_km",
    "route_travel_time_h", "cutout_travel_time_h", "boundary_travel_time_h",
    "tour_duration_h", "trips", "total_cost_eur",
    "total_cost_eur_inSimulation", "total_cost_eur_withBoundary"
  )
  for (col in setdiff(optional_cols, names(rows))) {
    rows[[col]] <- NA_real_
  }

  rows %>%
    mutate(
      boundary_distance_km = coalesce(boundary_distance_km, 0),
      boundary_travel_time_h = coalesce(boundary_travel_time_h, 0),
      distance_km_inSimulation = coalesce(
        cutout_distance_km,
        ifelse(!is.na(route_distance_km) & route_distance_km > 0, route_distance_km, NA_real_),
        distance_km
      ),
      travel_time_h_inSimulation = coalesce(
        cutout_travel_time_h,
        ifelse(!is.na(route_travel_time_h) & route_travel_time_h > 0, route_travel_time_h, NA_real_),
        travel_time_h
      ),
      tour_duration_h_inSimulation = tour_duration_h,
      distance_km_withBoundary = ifelse(
        is.na(distance_km_inSimulation) & boundary_distance_km == 0,
        NA_real_,
        coalesce(distance_km_inSimulation, 0) + boundary_distance_km
      ),
      travel_time_h_withBoundary = ifelse(
        is.na(travel_time_h_inSimulation) & boundary_travel_time_h == 0,
        NA_real_,
        coalesce(travel_time_h_inSimulation, 0) + boundary_travel_time_h
      ),
      tour_duration_h_withBoundary = ifelse(
        is.na(tour_duration_h_inSimulation),
        NA_real_,
        tour_duration_h_inSimulation + boundary_travel_time_h
      ),
      total_cost_eur_inSimulation = coalesce(total_cost_eur_inSimulation, total_cost_eur),
      total_cost_eur_withBoundary = coalesce(total_cost_eur_withBoundary, total_cost_eur),
      distance_km = distance_km_withBoundary,
      travel_time_h = travel_time_h_withBoundary,
      tour_duration_h = tour_duration_h_withBoundary,
      total_cost_eur = total_cost_eur_withBoundary
    )
}

# Uses tourAnalysis_distances for study-area-start distance scope and output_trips for travel time.
build_small_component_key_facts <- function(tour_distances, trip_measures, paths) {
  if (nrow(tour_distances) == 0) {
    return(tibble())
  }

  distance_summary <- tour_distances %>%
    mutate(
      component = paper_component_from_tour_group(.data$groupOfSubpopulation),
      person = as.character(.data$personId),
      distance_km_value = as_number(.data$distanceInKm)
    ) %>%
    filter(!is.na(component)) %>%
    group_by(component) %>%
    summarise(
      vehicles = n_distinct(person),
      tours = n(),
      distance_km = safe_sum(distance_km_value),
      .groups = "drop"
    )

  travel_time_summary <- build_small_component_travel_time_summary(tour_distances, trip_measures)

  distance_summary %>%
    left_join(travel_time_summary, by = "component") %>%
    mutate(
      total_cost_eur = NA_real_,
      paper_relevance = "paper_canonical_key_facts",
      sample_scope = paste0(paths$sample_tag, "pct_run"),
      evaluation_scope = "study_area_start",
      metric_scope = "study_area_start",
      source_dataset = "tourAnalysis_distances.csv;output_trips.csv joined with output_persons.csv",
      inclusion_rule = "commercialPersonTraffic and smallScaleGoodsTraffic agents contained in analysis/commercialTraffic/tourAnalysis_distances.csv",
      source_file = "analysis/commercialTraffic/tourAnalysis_distances.csv;output_trips.csv"
    ) %>%
    with_meta(paths, "analysis/commercialTraffic/tourAnalysis_distances.csv;output_trips.csv")
}

build_ltl_component_key_facts <- function(trip_components, paths) {
  if (!should_build_ltl_analyses(paths) || nrow(trip_components) == 0) {
    return(empty_trip_component_key_facts())
  }

  trip_components %>%
    filter(component %in% ltl_components())
}

attach_tour_durations_to_key_facts <- function(rows, tour_durations, tour_distances, person_scores, paths) {
  duration_summary <- build_tour_duration_summary(tour_durations, tour_distances, person_scores, paths)

  rows %>%
    left_join(duration_summary, by = "component") %>%
    mutate(
      source_dataset = ifelse(
        !is.na(tour_duration_h),
        paste_source_columns(source_dataset, tour_duration_source_dataset),
        source_dataset
      ),
      source_file = ifelse(
        !is.na(tour_duration_h),
        paste_source_columns(source_file, tour_duration_source_file),
        source_file
      )
    ) %>%
    select(-tour_duration_source_dataset, -tour_duration_source_file)
}

build_tour_duration_summary <- function(tour_durations, tour_distances, person_scores, paths) {
  empty <- tibble(
    component = character(),
    tour_duration_h = numeric(),
    tour_duration_source_dataset = character(),
    tour_duration_source_file = character()
  )
  if (nrow(tour_durations) == 0) {
    return(empty)
  }

  required <- c("personId", "groupOfSubpopulation", "vehicleType", "tourDurationsInHours")
  missing <- setdiff(required, names(tour_durations))
  if (length(missing) > 0) {
    stop("tourAnalysis_durations.csv misses required columns: ", paste(missing, collapse = ", "), call. = FALSE)
  }

  study_area_start_scope <- build_study_area_start_person_scope(tour_distances)
  person_lookup <- person_scores %>%
    group_by(person) %>%
    summarise(
      subpopulation = as.character(first_non_missing(subpopulation)),
      goods_type = as_number(first_non_missing(goods_type)),
      .groups = "drop"
    )

  tour_durations %>%
    transmute(
      person = as.character(.data$personId),
      vehicle_type = as.character(.data$vehicleType),
      duration_group = clean_subpopulation(.data$groupOfSubpopulation),
      tour_duration_h_value = as_number(.data$tourDurationsInHours)
    ) %>%
    left_join(person_lookup, by = "person") %>%
    mutate(
      component = coalesce(
        ifelse(
          duration_group == "LTL",
          paper_component_from_ltl_tour_person(person, vehicle_type),
          NA_character_
        ),
        paper_component_from_trip(subpopulation, goods_type),
        paper_component_from_cost_person(duration_group, goods_type)
      )
    ) %>%
    left_join(
      study_area_start_scope %>% mutate(in_study_area_start_scope = TRUE),
      by = c("component", "person")
    ) %>%
    mutate(
      in_study_area_start_scope = coalesce(in_study_area_start_scope, FALSE),
      include_tour_duration = case_when(
        component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic") ~ in_study_area_start_scope,
        component %in% ltl_components() ~ should_build_ltl_analyses(paths),
        component %in% c("FTL", "longDistanceFreight") ~ TRUE,
        TRUE ~ FALSE
      )
    ) %>%
    filter(include_tour_duration) %>%
    group_by(component) %>%
    summarise(
      tour_duration_h = safe_sum(tour_duration_h_value),
      tour_duration_source_dataset = "tourAnalysis_durations.csv",
      tour_duration_source_file = "analysis/commercialTraffic/tourAnalysis_durations.csv",
      .groups = "drop"
    )
}

build_small_component_travel_time_summary <- function(tour_distances, trip_measures) {
  empty <- tibble(component = character(), trips = numeric(), travel_time_h = numeric(), travel_time_source_file = character())
  if (nrow(trip_measures) == 0) {
    return(empty)
  }

  study_area_start_scope <- build_study_area_start_person_scope(tour_distances)
  if (nrow(study_area_start_scope) == 0) {
    return(empty)
  }

  study_area_start_scope %>%
    left_join(
      trip_measures %>%
        select(person, travel_time_h, trip_source_file = source_file) %>%
        mutate(trip_row_present = TRUE),
      by = "person"
    ) %>%
    group_by(component) %>%
    summarise(
      trips = sum(coalesce(trip_row_present, FALSE)),
      travel_time_h = safe_sum(ifelse(coalesce(trip_row_present, FALSE), travel_time_h, NA_real_)),
      travel_time_source_file = collapse_source_values(trip_source_file),
      .groups = "drop"
    )
}

empty_trip_component_key_facts <- function() {
  tibble(
    component = character(),
    vehicles = numeric(),
    tours = numeric(),
    trips = numeric(),
    distance_km = numeric(),
    travel_time_h = numeric(),
    total_cost_eur = numeric(),
    source_file = character(),
    paper_relevance = character(),
    sample_scope = character(),
    evaluation_scope = character(),
    metric_scope = character(),
    source_dataset = character(),
    inclusion_rule = character()
  )
}

# Builds the all-agent component scope from output_persons.csv.
build_trip_component_agent_scope <- function(person_scores, paths) {
  if (nrow(person_scores) == 0) {
    return(tibble(person = character(), component = character(), person_source_file = character()))
  }

  included_components <- c("FTL", "longDistanceFreight")
  if (should_build_ltl_analyses(paths)) {
    included_components <- c(ltl_components(), included_components)
  }

  person_scores %>%
    mutate(
      component = coalesce(
        paper_component_from_ltl_tour_person(person),
        paper_component_from_trip(subpopulation, goods_type),
        paper_component_from_freight_person_id(person)
      )
    ) %>%
    filter(component %in% included_components) %>%
    group_by(person) %>%
    summarise(
      component = as.character(first_non_missing(component)),
      person_source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    filter(!is.na(component))
}

# Uses output_persons.csv for all-agent scope and counts matching output_trips.csv rows.
build_trip_component_key_facts <- function(trip_measures, person_scores, paths) {
  agent_scope <- build_trip_component_agent_scope(person_scores, paths)
  if (nrow(agent_scope) == 0) {
    return(empty_trip_component_key_facts())
  }

  agent_summary <- agent_scope %>%
    group_by(component) %>%
    summarise(
      vehicles = n_distinct(person),
      person_source_file = collapse_source_values(person_source_file),
      .groups = "drop"
    )

  trip_summary <- agent_scope %>%
    left_join(
      trip_measures %>%
        select(person, traveled_distance_km, travel_time_h, trip_source_file = source_file) %>%
        mutate(trip_row_present = TRUE),
      by = "person"
    ) %>%
    group_by(component) %>%
    summarise(
      trips = sum(coalesce(trip_row_present, FALSE)),
      distance_km = safe_sum(ifelse(coalesce(trip_row_present, FALSE), traveled_distance_km, NA_real_)),
      travel_time_h = safe_sum(ifelse(coalesce(trip_row_present, FALSE), travel_time_h, NA_real_)),
      trip_source_file = collapse_source_values(trip_source_file),
      .groups = "drop"
    )

  agent_summary %>%
    left_join(trip_summary, by = "component") %>%
    mutate(
      tours = NA_real_,
      total_cost_eur = NA_real_,
      source_file = paste_source_columns(person_source_file, trip_source_file),
      paper_relevance = ifelse(
        component %in% c("FTL", "longDistanceFreight"),
        "interim_until_selected_plan_values_are_available",
        "paper_canonical_key_facts"
      ),
      sample_scope = paste0(paths$sample_tag, "pct_run"),
      evaluation_scope = "all_generated_agents",
      metric_scope = "represented_cutout",
      source_dataset = "output_persons.csv subpopulation scope joined to output_trips.csv by person",
      inclusion_rule = ifelse(
        component %in% ltl_components(),
        "wasteCollection, CEP and remainingLTL include all agents from output_persons.csv; trips are counted from output_trips.csv for those agents",
        "FTL and longDistanceFreight include all agents from output_persons.csv; trips are counted from output_trips.csv for those agents"
      )
    ) %>%
    select(
      component, vehicles, tours, trips, distance_km, travel_time_h, total_cost_eur,
      source_file, paper_relevance, sample_scope, evaluation_scope, metric_scope,
      source_dataset, inclusion_rule
    ) %>%
    copy_analysis_meta(trip_measures)
}

# Uses selected-plan boundary attributes for longDistanceFreight/FTL where full operational sections matter.
build_selected_long_distance_key_facts <- function(selected_plan_measures, trip_components, paths) {
  if (nrow(selected_plan_measures) == 0) {
    return(tibble())
  }

  boundary_summary <- selected_plan_measures %>%
    filter(paper_component %in% c("FTL", "longDistanceFreight"), source_role == "boundary") %>%
    group_by(component = paper_component) %>%
    summarise(
      boundary_vehicles = n_distinct(person),
      boundary_distance_km = safe_sum(selected_plan_boundary_distance_km),
      boundary_travel_time_h = safe_sum(selected_plan_boundary_travel_time_h),
      boundary_source_file = paste(unique(source_file), collapse = ";"),
      .groups = "drop"
    )

  route_summary <- selected_plan_measures %>%
    filter(paper_component %in% c("FTL", "longDistanceFreight"), source_role == "cutout_route") %>%
    group_by(component = paper_component) %>%
    summarise(
      route_vehicles = n_distinct(person),
      route_distance_km = safe_sum(selected_plan_route_distance_km),
      route_travel_time_h = safe_sum(selected_plan_route_travel_time_h),
      route_source_file = paste(unique(source_file), collapse = ";"),
      .groups = "drop"
    )

  selected_summary <- full_join(boundary_summary, route_summary, by = "component") %>%
    mutate(
      vehicles = coalesce(boundary_vehicles, route_vehicles),
      source_file = purrr::map2_chr(boundary_source_file, route_source_file, function(boundary_file, route_file) {
        paste(na.omit(unique(c(boundary_file, route_file))), collapse = ";")
      })
    )

  if (nrow(selected_summary) == 0) {
    return(tibble())
  }

  trip_cutout <- trip_components %>%
    filter(component %in% c("FTL", "longDistanceFreight")) %>%
    transmute(
      component,
      cutout_trips = trips,
      cutout_distance_km = distance_km,
      cutout_travel_time_h = travel_time_h,
      cutout_source_file = source_file
    )

  selected_summary %>%
    left_join(trip_cutout, by = "component") %>%
    mutate(
      source_file = paste_source_columns(source_file, cutout_source_file),
      has_boundary_attributes = !is.na(boundary_distance_km) & boundary_distance_km > 0,
      has_cutout_distance = !is.na(cutout_distance_km),
      has_route_distance = !is.na(route_distance_km) & route_distance_km > 0,
      has_cutout_travel_time = !is.na(cutout_travel_time_h),
      has_route_travel_time = !is.na(route_travel_time_h) & route_travel_time_h > 0,
      distance_km = case_when(
        has_boundary_attributes & has_cutout_distance ~ cutout_distance_km + boundary_distance_km,
        has_boundary_attributes & has_route_distance ~ route_distance_km + boundary_distance_km,
        has_cutout_distance ~ cutout_distance_km,
        TRUE ~ route_distance_km
      ),
      travel_time_h = case_when(
        has_boundary_attributes & has_cutout_travel_time ~ cutout_travel_time_h + boundary_travel_time_h,
        has_boundary_attributes & has_route_travel_time ~ route_travel_time_h + boundary_travel_time_h,
        has_cutout_travel_time ~ cutout_travel_time_h,
        TRUE ~ route_travel_time_h
      ),
      trips = cutout_trips,
      tours = NA_real_,
      total_cost_eur = NA_real_,
      paper_relevance = "paper_canonical_key_facts",
      sample_scope = paste0(paths$sample_tag, "pct_run"),
      evaluation_scope = "all_generated_agents",
      metric_scope = ifelse(
        has_boundary_attributes & (has_cutout_travel_time | has_route_travel_time),
        "boundary_adjusted_operational",
        "selected_plan_or_trip"
      ),
      source_dataset = ifelse(
        has_boundary_attributes & (has_cutout_travel_time | has_route_travel_time),
        "output_trips.csv + final selected plans + component source plans XML boundary attributes",
        "selected plans XML or output_trips.csv"
      ),
      inclusion_rule = "longDistanceFreight and FTL include all agents; boundary distances from component plans are added to represented cutout trip distance, and boundary times are added to final selected-plan route time"
    ) %>%
    select(
      component, vehicles, tours, trips, distance_km, travel_time_h, total_cost_eur,
      route_distance_km, route_travel_time_h, boundary_distance_km, boundary_travel_time_h,
      cutout_distance_km, cutout_travel_time_h, has_boundary_attributes, has_route_travel_time,
      paper_relevance, sample_scope, evaluation_scope, metric_scope,
      source_dataset, inclusion_rule, source_file
    ) %>%
    copy_analysis_meta(selected_plan_measures)
}

# Reconstructs MATSim-style operating costs from selected plans and vehicle-type cost parameters.
build_paper_costs_by_component <- function(selected_plan_cost_legs, selected_plan_cost_activities, selected_plan_measures,
                                           vehicle_cost_parameters, tour_distances, paths) {
  if (nrow(selected_plan_cost_legs) == 0 || nrow(vehicle_cost_parameters) == 0) {
    return(tibble())
  }

  study_area_start_scope <- build_study_area_start_person_scope(tour_distances)
  eligible_legs <- selected_plan_cost_legs %>%
    filter(!is.na(paper_component)) %>%
    left_join(
      study_area_start_scope %>% mutate(in_study_area_start_scope = TRUE),
      by = c("paper_component" = "component", "person" = "person")
    ) %>%
    mutate(
      in_study_area_start_scope = coalesce(in_study_area_start_scope, FALSE),
      include_in_paper_cost_filter = case_when(
        paper_component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic") ~ in_study_area_start_scope,
        paper_component %in% ltl_components() ~ should_build_ltl_analyses(paths),
        paper_component %in% c("FTL", "longDistanceFreight") ~ TRUE,
        TRUE ~ FALSE
      )
    ) %>%
    filter(include_in_paper_cost_filter) %>%
    prefer_cutout_cost_sources()

  if (nrow(eligible_legs) == 0) {
    add_warning("No selected-plan legs remain after applying paper cost scopes.")
    return(tibble())
  }
  eligible_activities <- apply_paper_cost_filter_to_activities(
    selected_plan_cost_activities,
    study_area_start_scope,
    paths
  ) %>%
    prefer_cutout_cost_sources()

  cost_years <- sort(unique(as.integer(vehicle_cost_parameters$cost_result_year)))
  expanded_legs <- eligible_legs %>%
    mutate(.leg_row_id = row_number()) %>%
    tidyr::expand_grid(cost_result_year = cost_years)

  costed_legs <- attach_vehicle_cost_rates(expanded_legs, vehicle_cost_parameters) %>%
    mutate(
      distance_cost_eur = route_distance_m * costs_per_meter,
      time_cost_eur = route_travel_time_s * costs_per_second,
      variable_cost_eur = distance_cost_eur + time_cost_eur
    )

  missing_rates <- costed_legs %>%
    filter(missing_cost_rate) %>%
    distinct(cost_result_year, leg_vehicle_type)
  if (nrow(missing_rates) > 0) {
    add_warning(
      "Missing vehicle cost rates for selected-plan cost reconstruction: ",
      paste(paste0(missing_rates$leg_vehicle_type, " (", missing_rates$cost_result_year, ")"), collapse = "; ")
    )
  }

  variable_costs <- costed_legs %>%
    group_by(cost_result_year, component = paper_component, person) %>%
    summarise(
      route_distance_m = sum(coalesce(route_distance_m, 0), na.rm = TRUE),
      route_travel_time_s = sum(coalesce(route_travel_time_s, 0), na.rm = TRUE),
      distance_cost_eur = sum(coalesce(distance_cost_eur, 0), na.rm = TRUE),
      time_cost_eur = sum(coalesce(time_cost_eur, 0), na.rm = TRUE),
      missing_cost_rate_legs = sum(as.integer(coalesce(missing_cost_rate, FALSE)), na.rm = TRUE),
      plan_source_file = collapse_source_values(source_file),
      cost_vehicle_source_file = collapse_source_values(cost_vehicle_source_file),
      .groups = "drop"
    )

  fixed_costs <- costed_legs %>%
    filter(!is.na(leg_vehicle_type), nzchar(leg_vehicle_type)) %>%
    distinct(cost_result_year, paper_component, person, cost_vehicle_type, fixed_costs_per_day, cost_vehicle_source_file) %>%
    group_by(cost_result_year, component = paper_component, person) %>%
    summarise(
      fixed_cost_eur = sum(coalesce(fixed_costs_per_day, 0), na.rm = TRUE),
      fixed_cost_source_file = collapse_source_values(cost_vehicle_source_file),
      .groups = "drop"
    )

  activity_costs <- build_activity_time_costs(eligible_activities, vehicle_cost_parameters, cost_years)
  boundary_costs <- build_boundary_cost_additions(selected_plan_measures, costed_legs)

  person_costs <- variable_costs %>%
    left_join(fixed_costs, by = c("cost_result_year", "component", "person")) %>%
    left_join(activity_costs, by = c("cost_result_year", "component", "person")) %>%
    left_join(boundary_costs, by = c("cost_result_year", "component", "person")) %>%
    mutate(
      fixed_cost_eur = coalesce(fixed_cost_eur, 0),
      activity_time_s = coalesce(activity_time_s, 0),
      activity_time_cost_eur = coalesce(activity_time_cost_eur, 0),
      activity_missing_cost_rate_activities = coalesce(activity_missing_cost_rate_activities, 0),
      boundary_distance_m = coalesce(boundary_distance_m, 0),
      boundary_travel_time_s = coalesce(boundary_travel_time_s, 0),
      boundary_distance_cost_eur = coalesce(boundary_distance_cost_eur, 0),
      boundary_time_cost_eur = coalesce(boundary_time_cost_eur, 0),
      total_distance_m = route_distance_m + boundary_distance_m,
      total_travel_time_s = route_travel_time_s + boundary_travel_time_s,
      total_cost_eur = fixed_cost_eur + distance_cost_eur + time_cost_eur +
        activity_time_cost_eur + boundary_distance_cost_eur + boundary_time_cost_eur,
      source_file = paste_source_columns(
        plan_source_file, cost_vehicle_source_file, fixed_cost_source_file,
        activity_source_file, boundary_source_file
      )
    ) %>%
    copy_analysis_meta(selected_plan_cost_legs)

  component_rows <- person_costs %>%
    group_by(cost_result_year, component) %>%
    summarise(
      component_group = component_group_from_paper_component(first(component)),
      row_type = "component",
      vehicles = n_distinct(person),
      route_distance_km = sum(coalesce(route_distance_m, 0), na.rm = TRUE) / 1000,
      boundary_distance_km = sum(coalesce(boundary_distance_m, 0), na.rm = TRUE) / 1000,
      distance_km = sum(coalesce(total_distance_m, 0), na.rm = TRUE) / 1000,
      route_travel_time_h = sum(coalesce(route_travel_time_s, 0), na.rm = TRUE) / 3600,
      boundary_travel_time_h = sum(coalesce(boundary_travel_time_s, 0), na.rm = TRUE) / 3600,
      travel_time_h = sum(coalesce(total_travel_time_s, 0), na.rm = TRUE) / 3600,
      activity_time_h = sum(coalesce(activity_time_s, 0), na.rm = TRUE) / 3600,
      fixed_cost_eur = sum(coalesce(fixed_cost_eur, 0), na.rm = TRUE),
      distance_cost_eur = sum(coalesce(distance_cost_eur, 0), na.rm = TRUE),
      time_cost_eur = sum(coalesce(time_cost_eur, 0), na.rm = TRUE),
      activity_time_cost_eur = sum(coalesce(activity_time_cost_eur, 0), na.rm = TRUE),
      boundary_distance_cost_eur = sum(coalesce(boundary_distance_cost_eur, 0), na.rm = TRUE),
      boundary_time_cost_eur = sum(coalesce(boundary_time_cost_eur, 0), na.rm = TRUE),
      total_cost_eur = sum(coalesce(total_cost_eur, 0), na.rm = TRUE),
      missing_cost_rate_legs = sum(coalesce(missing_cost_rate_legs, 0), na.rm = TRUE) +
        sum(coalesce(activity_missing_cost_rate_activities, 0), na.rm = TRUE),
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    mutate(
      included_in_comparable_cost = !component %in% comparable_cost_excluded_components(paths$model_type),
      comparable_cost_eur = ifelse(included_in_comparable_cost, total_cost_eur, 0),
      source_dataset = source_dataset_label_for_costs(component),
      paper_relevance = "paper_canonical_costs",
      sample_scope = paste0(paths$sample_tag, "pct_run"),
      evaluation_scope = ifelse(
        component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic"),
        "study_area_start",
        "all_generated_agents"
      ),
      metric_scope = ifelse(
        component %in% c("FTL", "longDistanceFreight") & boundary_distance_km > 0,
        "boundary_adjusted_operational",
        "represented_selected_plan"
      ),
      inclusion_rule = cost_inclusion_rule(component)
    ) %>%
    copy_analysis_meta(person_costs)

  bind_rows(component_rows, build_paper_cost_total_rows(component_rows, paths)) %>%
    arrange(
      cost_result_year,
      match(component, c(
        "commercialPersonTraffic", "smallScaleGoodsTraffic", "wasteCollection",
        "CEP", "remainingLTL", "LTL", "FTL", "longDistanceFreight",
        "small-scale goods total", "long-distance total", "all components", "comparable cost total"
      ))
    )
}

# Keeps the study-area-start scope identical to analysis/commercialTraffic/tourAnalysis_distances.csv.
build_study_area_start_person_scope <- function(tour_distances) {
  if (nrow(tour_distances) == 0) {
    return(tibble(component = character(), person = character()))
  }
  if (!"personId" %in% names(tour_distances)) {
    stop("tourAnalysis_distances.csv misses required column: personId", call. = FALSE)
  }

  tour_distances %>%
    mutate(
      component = paper_component_from_tour_group(.data$groupOfSubpopulation),
      person = as.character(.data$personId)
    ) %>%
    filter(!is.na(component), !is.na(person), nzchar(person)) %>%
    distinct(component, person)
}

# Maps the study-area-start agents to the KWM carriers represented in their logs.
build_range_log_start_scope <- function(tour_distances, person_scores) {
  if (nrow(tour_distances) == 0 || nrow(person_scores) == 0) {
    return(tibble(component = character(), carrier_id = character()))
  }

  study_area_start_scope <- build_study_area_start_person_scope(tour_distances)
  if (nrow(study_area_start_scope) == 0) {
    return(tibble(component = character(), carrier_id = character()))
  }

  person_components <- person_scores %>%
    transmute(
      person,
      component = paper_component_from_cost_person(subpopulation, goods_type),
      carrier_id = as.character(.data$carrier_id)
    ) %>%
    filter(
      component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic"),
      !is.na(carrier_id),
      nzchar(carrier_id)
    )

  scoped_persons <- person_components %>%
    inner_join(study_area_start_scope, by = c("component", "person"))

  carrier_scope <- scoped_persons %>%
    distinct(component, carrier_id)

  attr(
    carrier_scope,
    "agent_counts"
  ) <- scoped_persons %>%
    count(component, name = "agents_in_study_area_start_scope")

  carrier_agent_counts <- person_components %>%
    group_by(component, carrier_id) %>%
    summarise(all_agents = n_distinct(person), .groups = "drop")
  scoped_carrier_agent_counts <- scoped_persons %>%
    group_by(component, carrier_id) %>%
    summarise(start_scope_agents = n_distinct(person), .groups = "drop")
  mixed_carriers <- carrier_agent_counts %>%
    inner_join(scoped_carrier_agent_counts, by = c("component", "carrier_id")) %>%
    filter(start_scope_agents < all_agents)
  if (nrow(mixed_carriers) > 0) {
    add_warning(
      nrow(mixed_carriers),
      " KWM carriers contain both study-area-start and outside-start agents. Their service logs are retained at carrier level."
    )
  }

  carrier_scope
}

# Applies the same paper component scope to activity rows as to route-leg cost rows.
apply_paper_cost_filter_to_activities <- function(selected_plan_cost_activities, study_area_start_scope, paths) {
  if (is.null(selected_plan_cost_activities) || nrow(selected_plan_cost_activities) == 0) {
    return(tibble())
  }

  selected_plan_cost_activities %>%
    filter(!is.na(paper_component), activity_duration_s > 0) %>%
    left_join(
      study_area_start_scope %>% mutate(in_study_area_start_scope = TRUE),
      by = c("paper_component" = "component", "person" = "person")
    ) %>%
    mutate(
      in_study_area_start_scope = coalesce(in_study_area_start_scope, FALSE),
      include_in_paper_cost_filter = case_when(
        paper_component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic") ~ in_study_area_start_scope,
        paper_component %in% ltl_components() ~ should_build_ltl_analyses(paths),
        paper_component %in% c("FTL", "longDistanceFreight") ~ TRUE,
        TRUE ~ FALSE
      )
    ) %>%
    filter(include_in_paper_cost_filter)
}

prefer_cutout_cost_sources <- function(rows) {
  if (is.null(rows) || nrow(rows) == 0 || !"source_role" %in% names(rows)) {
    return(rows)
  }

  rows %>%
    mutate(source_role = coalesce(as.character(source_role), "")) %>%
    group_by(paper_component, person) %>%
    mutate(has_cutout_cost_source = any(source_role == "cutout_route")) %>%
    ungroup() %>%
    filter(!has_cutout_cost_source | source_role == "cutout_route") %>%
    select(-has_cutout_cost_source)
}

# Builds per-person service/handling time costs from selected-plan activities.
build_activity_time_costs <- function(eligible_activities, vehicle_cost_parameters, cost_years) {
  if (is.null(eligible_activities) || nrow(eligible_activities) == 0) {
    return(empty_activity_time_costs())
  }

  expanded_activities <- eligible_activities %>%
    transmute(
      person,
      paper_component,
      activity_index,
      activity_type,
      activity_duration_s,
      leg_vehicle_type = activity_vehicle_type,
      source_file
    ) %>%
    mutate(.activity_row_id = row_number()) %>%
    tidyr::expand_grid(cost_result_year = cost_years)

  costed_activities <- attach_vehicle_cost_rates(expanded_activities, vehicle_cost_parameters) %>%
    mutate(activity_time_cost_eur = activity_duration_s * costs_per_second)

  missing_rates <- costed_activities %>%
    filter(missing_cost_rate) %>%
    distinct(cost_result_year, leg_vehicle_type)
  if (nrow(missing_rates) > 0) {
    add_warning(
      "Missing vehicle cost rates for selected-plan activity-cost reconstruction: ",
      paste(paste0(missing_rates$leg_vehicle_type, " (", missing_rates$cost_result_year, ")"), collapse = "; ")
    )
  }

  costed_activities %>%
    group_by(cost_result_year, component = paper_component, person) %>%
    summarise(
      activity_time_s = sum(coalesce(activity_duration_s, 0), na.rm = TRUE),
      activity_time_cost_eur = sum(coalesce(activity_time_cost_eur, 0), na.rm = TRUE),
      activity_missing_cost_rate_activities = sum(as.integer(coalesce(missing_cost_rate, FALSE)), na.rm = TRUE),
      activity_source_file = collapse_source_values(source_file),
      .groups = "drop"
    )
}

# Provides the activity-cost schema when no score-relevant selected-plan activities are present.
empty_activity_time_costs <- function() {
  tibble(
    cost_result_year = integer(),
    component = character(),
    person = character(),
    activity_time_s = numeric(),
    activity_time_cost_eur = numeric(),
    activity_missing_cost_rate_activities = numeric(),
    activity_source_file = character()
  )
}

# Adds exact vehicle cost rates to expanded selected-plan legs.
attach_vehicle_cost_rates <- function(expanded_legs, vehicle_cost_parameters) {
  rates <- vehicle_cost_parameters %>%
    transmute(
      cost_result_year = as.integer(cost_result_year),
      cost_vehicle_type = vehicle_type,
      fixed_costs_per_day,
      costs_per_meter,
      costs_per_second,
      cost_vehicle_source_file = source_file
    ) %>%
    distinct()

  # Recharge vehicle types can be created dynamically during KWM tour planning.
  # Future price-year files may therefore contain the base vehicle type but not
  # every generated ID such as ID.3_1Recharge. Keep an explicitly priced type
  # when it exists and use the non-Recharge base type only as a fallback.
  exact_rates <- rates %>%
    rename(
      exact_fixed_costs_per_day = fixed_costs_per_day,
      exact_costs_per_meter = costs_per_meter,
      exact_costs_per_second = costs_per_second,
      exact_cost_vehicle_source_file = cost_vehicle_source_file
    )

  base_rates <- rates %>%
    filter(!is_recharge_vehicle_type(cost_vehicle_type)) %>%
    transmute(
      cost_result_year,
      base_vehicle_type = cost_vehicle_type,
      base_fixed_costs_per_day = fixed_costs_per_day,
      base_costs_per_meter = costs_per_meter,
      base_costs_per_second = costs_per_second,
      base_cost_vehicle_source_file = cost_vehicle_source_file
    )

  exact <- expanded_legs %>%
    left_join(
      exact_rates,
      by = c("cost_result_year", "leg_vehicle_type" = "cost_vehicle_type")
    ) %>%
    mutate(base_vehicle_type = normalize_vehicle_type(leg_vehicle_type)) %>%
    left_join(base_rates, by = c("cost_result_year", "base_vehicle_type")) %>%
    mutate(
      fixed_costs_per_day = coalesce(exact_fixed_costs_per_day, base_fixed_costs_per_day),
      costs_per_meter = coalesce(exact_costs_per_meter, base_costs_per_meter),
      costs_per_second = coalesce(exact_costs_per_second, base_costs_per_second),
      cost_vehicle_source_file = coalesce(
        exact_cost_vehicle_source_file,
        base_cost_vehicle_source_file
      ),
      # Keep the selected-plan vehicle type here. It is used to distinguish
      # separately used base and Recharge vehicles when fixed costs are summed.
      cost_vehicle_type = leg_vehicle_type
    ) %>%
    select(
      -exact_fixed_costs_per_day, -exact_costs_per_meter, -exact_costs_per_second,
      -exact_cost_vehicle_source_file, -base_vehicle_type,
      -base_fixed_costs_per_day, -base_costs_per_meter, -base_costs_per_second,
      -base_cost_vehicle_source_file
    )

  exact %>%
    mutate(
      missing_cost_rate = is.na(fixed_costs_per_day) & is.na(costs_per_meter) & is.na(costs_per_second),
      costs_per_meter = ifelse(is.na(costs_per_meter), 0, costs_per_meter),
      costs_per_second = ifelse(is.na(costs_per_second), 0, costs_per_second),
      fixed_costs_per_day = ifelse(is.na(fixed_costs_per_day), 0, fixed_costs_per_day)
    )
}

# Calculates variable boundary costs for FTL and longDistanceFreight without adding fixed costs again.
build_boundary_cost_additions <- function(selected_plan_measures, costed_legs) {
  if (nrow(selected_plan_measures) == 0) {
    return(empty_boundary_cost_additions())
  }

  boundary_measures <- selected_plan_measures %>%
    filter(paper_component %in% c("FTL", "longDistanceFreight"), source_role == "boundary") %>%
    group_by(component = paper_component, person) %>%
    summarise(
      boundary_distance_m = sum(coalesce(selected_plan_boundary_distance_m, 0), na.rm = TRUE),
      boundary_travel_time_s = sum(coalesce(selected_plan_boundary_travel_time_s, 0), na.rm = TRUE),
      boundary_source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    filter(boundary_distance_m > 0 | boundary_travel_time_s > 0)
  if (nrow(boundary_measures) == 0) {
    return(empty_boundary_cost_additions())
  }

  person_rates <- costed_legs %>%
    filter(paper_component %in% c("FTL", "longDistanceFreight")) %>%
    arrange(cost_result_year, paper_component, person, leg_index) %>%
    group_by(cost_result_year, component = paper_component, person) %>%
    summarise(
      boundary_vehicle_type = first(leg_vehicle_type),
      boundary_costs_per_meter = first(costs_per_meter),
      boundary_costs_per_second = first(costs_per_second),
      boundary_rate_source_file = first(cost_vehicle_source_file),
      .groups = "drop"
    )

  person_rates %>%
    inner_join(boundary_measures, by = c("component", "person")) %>%
    mutate(
      boundary_distance_cost_eur = boundary_distance_m * boundary_costs_per_meter,
      boundary_time_cost_eur = boundary_travel_time_s * boundary_costs_per_second,
      boundary_source_file = paste_source_columns(boundary_source_file, boundary_rate_source_file)
    ) %>%
    select(
      cost_result_year, component, person, boundary_distance_m, boundary_travel_time_s,
      boundary_distance_cost_eur, boundary_time_cost_eur, boundary_vehicle_type, boundary_source_file
    )
}

# Provides the boundary-cost schema when no boundary additions are available.
empty_boundary_cost_additions <- function() {
  tibble(
    cost_result_year = integer(),
    component = character(),
    person = character(),
    boundary_distance_m = numeric(),
    boundary_travel_time_s = numeric(),
    boundary_distance_cost_eur = numeric(),
    boundary_time_cost_eur = numeric(),
    boundary_vehicle_type = character(),
    boundary_source_file = character()
  )
}

# Builds total rows for cost CSVs, including the comparable-cost total used in technology comparisons.
build_paper_cost_total_rows <- function(component_rows, paths) {
  if (nrow(component_rows) == 0) {
    return(tibble())
  }

  small_scale_total_components <- "smallScaleGoodsTraffic"
  ltl_total <- tibble()
  if (should_build_ltl_analyses(paths)) {
    small_scale_total_components <- c(small_scale_total_components, ltl_components())
    ltl_total <- summarise_paper_cost_total(component_rows, "LTL", ltl_components(), FALSE)
  }

  bind_rows(
    ltl_total,
    summarise_paper_cost_total(component_rows, "small-scale goods total", small_scale_total_components, FALSE),
    summarise_paper_cost_total(component_rows, "long-distance total", c("FTL", "longDistanceFreight"), FALSE),
    summarise_paper_cost_total(component_rows, "all components", unique(component_rows$component), FALSE),
    summarise_paper_cost_total(
      component_rows,
      "comparable cost total",
      component_rows %>% filter(included_in_comparable_cost) %>% pull(component) %>% unique(),
      TRUE
    )
  )
}

# Sums cost rows for one named total.
summarise_paper_cost_total <- function(component_rows, component, source_components, comparable_total) {
  selected <- component_rows %>% filter(component %in% source_components)
  if (nrow(selected) == 0) {
    return(tibble())
  }

  selected %>%
    group_by(cost_result_year) %>%
    summarise(
      component_group = "total",
      row_type = "total",
      component = .env$component,
      vehicles = sum(coalesce(vehicles, 0), na.rm = TRUE),
      route_distance_km = sum(coalesce(route_distance_km, 0), na.rm = TRUE),
      boundary_distance_km = sum(coalesce(boundary_distance_km, 0), na.rm = TRUE),
      distance_km = sum(coalesce(distance_km, 0), na.rm = TRUE),
      route_travel_time_h = sum(coalesce(route_travel_time_h, 0), na.rm = TRUE),
      boundary_travel_time_h = sum(coalesce(boundary_travel_time_h, 0), na.rm = TRUE),
      travel_time_h = sum(coalesce(travel_time_h, 0), na.rm = TRUE),
      activity_time_h = sum(coalesce(activity_time_h, 0), na.rm = TRUE),
      fixed_cost_eur = sum(coalesce(fixed_cost_eur, 0), na.rm = TRUE),
      distance_cost_eur = sum(coalesce(distance_cost_eur, 0), na.rm = TRUE),
      time_cost_eur = sum(coalesce(time_cost_eur, 0), na.rm = TRUE),
      activity_time_cost_eur = sum(coalesce(activity_time_cost_eur, 0), na.rm = TRUE),
      boundary_distance_cost_eur = sum(coalesce(boundary_distance_cost_eur, 0), na.rm = TRUE),
      boundary_time_cost_eur = sum(coalesce(boundary_time_cost_eur, 0), na.rm = TRUE),
      total_cost_eur = sum(coalesce(total_cost_eur, 0), na.rm = TRUE),
      missing_cost_rate_legs = sum(coalesce(missing_cost_rate_legs, 0), na.rm = TRUE),
      included_in_comparable_cost = comparable_total,
      comparable_cost_eur = sum(coalesce(comparable_cost_eur, 0), na.rm = TRUE),
      source_dataset = paste(unique(source_dataset), collapse = ";"),
      paper_relevance = ifelse(comparable_total, "paper_comparable_cost_total", "paper_cost_total"),
      sample_scope = first(sample_scope),
      evaluation_scope = "component_specific",
      metric_scope = paste(unique(metric_scope), collapse = ";"),
      inclusion_rule = paste(unique(inclusion_rule), collapse = " | "),
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    copy_analysis_meta(selected)
}

# Describes the raw input files used for reconstructed operating costs.
source_dataset_label_for_costs <- function(component) {
  ifelse(
    component %in% c("FTL", "longDistanceFreight"),
    "final selected plans + component source plans XML boundary attributes + mode-vehicles costInformation",
    "final selected plans + mode-vehicles costInformation"
  )
}

# Documents the component-specific cost inclusion rule used by the paper.
cost_inclusion_rule <- function(component) {
  case_when(
    component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic") ~
      "Only agents contained in analysis/commercialTraffic/tourAnalysis_distances.csv are included.",
    component %in% c("wasteCollection", "CEP", "remainingLTL") ~
      "All generated LTL agents from the final run selected plans are included.",
    component %in% c("FTL", "longDistanceFreight") ~
      "All generated agents are included; external boundary distance and travel time are added as variable operating cost only.",
    TRUE ~ "Component-specific cost inclusion follows the paper key-facts scope."
  )
}

# Builds paper total rows from the component rows without changing their component-level source policy.
build_paper_key_facts_total <- function(paper_key_facts_by_component) {
  if (nrow(paper_key_facts_by_component) == 0) {
    return(tibble())
  }

  small_scale_total_components <- "smallScaleGoodsTraffic"
  ltl_total <- tibble()
  if (any(paper_key_facts_by_component$component %in% ltl_components())) {
    small_scale_total_components <- c(small_scale_total_components, ltl_components())
    ltl_total <- summarise_paper_total(paper_key_facts_by_component, "LTL", ltl_components())
  }

  bind_rows(
    ltl_total,
    summarise_paper_total(paper_key_facts_by_component, "small-scale goods total", small_scale_total_components),
    summarise_paper_total(paper_key_facts_by_component, "long-distance total", c("FTL", "longDistanceFreight")),
    summarise_paper_total(paper_key_facts_by_component, "all components", unique(paper_key_facts_by_component$component))
  )
}

# Sums one named total over a fixed set of paper components.
summarise_paper_total <- function(rows, component, source_components) {
  selected <- rows %>% filter(component %in% source_components)
  if (nrow(selected) == 0) {
    return(tibble())
  }

  selected %>%
    summarise(
      component_group = "total",
      component = .env$component,
      paper_relevance = "paper_canonical_total",
      sample_scope = first(sample_scope),
      evaluation_scope = "component_specific",
      metric_scope = paste(unique(metric_scope), collapse = ";"),
      source_dataset = paste(unique(source_dataset), collapse = ";"),
      inclusion_rule = paste(unique(inclusion_rule), collapse = " | "),
      vehicles = safe_sum(vehicles),
      tours = safe_sum(tours),
      trips = safe_sum(trips),
      distance_km_inSimulation = safe_sum(distance_km_inSimulation),
      distance_km_withBoundary = safe_sum(distance_km_withBoundary),
      travel_time_h_inSimulation = safe_sum(travel_time_h_inSimulation),
      travel_time_h_withBoundary = safe_sum(travel_time_h_withBoundary),
      tour_duration_h_inSimulation = safe_sum(tour_duration_h_inSimulation),
      tour_duration_h_withBoundary = safe_sum(tour_duration_h_withBoundary),
      distance_km = distance_km_withBoundary,
      travel_time_h = travel_time_h_withBoundary,
      tour_duration_h = tour_duration_h_withBoundary,
      total_cost_eur_inSimulation = safe_sum(total_cost_eur_inSimulation),
      total_cost_eur_withBoundary = safe_sum(total_cost_eur_withBoundary),
      total_cost_eur = total_cost_eur_withBoundary,
      avg_distance_km_inSimulation_per_vehicle = safe_divide(distance_km_inSimulation, vehicles),
      avg_distance_km_withBoundary_per_vehicle = safe_divide(distance_km_withBoundary, vehicles),
      avg_travel_time_h_inSimulation_per_vehicle = safe_divide(travel_time_h_inSimulation, vehicles),
      avg_travel_time_h_withBoundary_per_vehicle = safe_divide(travel_time_h_withBoundary, vehicles),
      avg_tour_duration_h_inSimulation_per_vehicle = safe_divide(tour_duration_h_inSimulation, vehicles),
      avg_tour_duration_h_withBoundary_per_vehicle = safe_divide(tour_duration_h_withBoundary, vehicles),
      avg_distance_km_per_vehicle = avg_distance_km_withBoundary_per_vehicle,
      avg_travel_time_h_per_vehicle = avg_travel_time_h_withBoundary_per_vehicle,
      avg_tour_duration_h_per_vehicle = avg_tour_duration_h_withBoundary_per_vehicle,
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    copy_analysis_meta(rows)
}

# Maps emission groups to model-component rows where no LTL detail split is available.
paper_component_from_emission_group <- function(group) {
  normalized <- clean_subpopulation(group)

  case_when(
    normalized == "commercialPersonTraffic" ~ "commercialPersonTraffic",
    normalized %in% c("goodsTraffic", "smallScaleGoodsTraffic") ~ "smallScaleGoodsTraffic",
    normalized == "FTL" ~ "FTL",
    normalized == "longDistanceFreight" ~ "longDistanceFreight",
    normalized == "LTL" ~ "LTL",
    TRUE ~ normalized
  )
}

validate_paper_analysis_outputs <- function(tour_distances, person_scores, energy_emissions,
                                            paper_costs_by_component, paper_key_facts_by_component, paths) {
  has_ltl_tours <- nrow(tour_distances) > 0 &&
    "groupOfSubpopulation" %in% names(tour_distances) &&
    any(clean_subpopulation(tour_distances$groupOfSubpopulation) == "LTL", na.rm = TRUE)
  has_ltl_key_facts <- nrow(paper_key_facts_by_component) > 0 &&
    "component" %in% names(paper_key_facts_by_component) &&
    any(paper_key_facts_by_component$component %in% c(ltl_components(), "LTL"), na.rm = TRUE)
  if (should_build_ltl_analyses(paths) && has_ltl_tours && !has_ltl_key_facts) {
    add_warning(
      "Advanced run contains LTL tours, but paper_key_facts_by_component has no LTL component rows."
    )
  }

  all_agent_components <- c(ltl_components(), "FTL", "longDistanceFreight")
  expected_all_agent_scope <- build_trip_component_agent_scope(person_scores, paths) %>%
    filter(component %in% all_agent_components) %>%
    count(component, name = "expected_agents")
  actual_all_agent_scope <- paper_key_facts_by_component %>%
    filter(component %in% all_agent_components) %>%
    transmute(component, actual_agents = vehicles)
  scope_mismatch <- expected_all_agent_scope %>%
    full_join(actual_all_agent_scope, by = "component") %>%
    mutate(
      expected_agents = coalesce(expected_agents, 0),
      actual_agents = coalesce(actual_agents, 0)
    ) %>%
    filter(expected_agents != actual_agents)
  if (nrow(scope_mismatch) > 0) {
    add_warning(
      "All-agent scope mismatch for paper key facts: ",
      paste(paste0(scope_mismatch$component, " expected ", scope_mismatch$expected_agents,
                   ", got ", scope_mismatch$actual_agents), collapse = "; ")
    )
  }

  ftl_key_rows <- tibble()
  if (nrow(paper_key_facts_by_component) > 0 && "component" %in% names(paper_key_facts_by_component)) {
    ftl_key_rows <- paper_key_facts_by_component %>% filter(component == "FTL")
  }
  has_ftl_key_results <- nrow(ftl_key_rows) > 0 &&
    any(coalesce(ftl_key_rows$vehicles, 0) > 0 | coalesce(ftl_key_rows$distance_km, 0) > 0, na.rm = TRUE)
  has_ftl_cost <- nrow(paper_costs_by_component) > 0 &&
    all(c("row_type", "component", "cost_result_year", "total_cost_eur") %in% names(paper_costs_by_component)) &&
    any(
      paper_costs_by_component$row_type == "component" &
        paper_costs_by_component$component == "FTL" &
        as.integer(paper_costs_by_component$cost_result_year) == as.integer(paths$year) &
        coalesce(paper_costs_by_component$total_cost_eur, 0) > 0,
      na.rm = TRUE
    )
  if (has_ftl_key_results && !has_ftl_cost) {
    add_warning("FTL has vehicles or distance in key facts, but no run-year cost row.")
  }

  ev_heavy40t_as_diesel <- is_bev_fleet(paths) &&
    nrow(energy_emissions) > 0 &&
    all(c("vehicle_type_base", "powertrain") %in% names(energy_emissions)) &&
    any(energy_emissions$vehicle_type_base == "heavy40t" & energy_emissions$powertrain == "diesel", na.rm = TRUE)
  if (ev_heavy40t_as_diesel) {
    stop("EV heavy40t is mapped to diesel in WTW emissions.", call. = FALSE)
  }

  invisible(TRUE)
}

# Builds paper-table-shaped CSV rows for one selected run.
build_paper_table_outputs <- function(tour_distances, tour_durations, jobs_per_tour, person_scores,
                                      selected_plan_cost_legs,
                                      energy_emissions, paper_costs_by_component,
                                      paper_key_facts_by_component, paper_key_facts_total,
                                      paper_comparable_costs, carrier_kpis,
                                      carrier_time_distance_vehicle_type, range_logs, paths) {
  sample_by_component <- build_paper_table_sample_size_by_component(
    tour_distances,
    tour_durations,
    jobs_per_tour,
    paper_key_facts_by_component,
    carrier_kpis,
    paths
  )

  tables <- list(
    paper_table_sample_size = build_paper_table_sample_size(sample_by_component, paths),
    paper_table_key_facts = build_paper_table_key_facts(
      paper_key_facts_by_component,
      paper_key_facts_total,
      paper_comparable_costs,
      paths
    ),
    paper_table_per_vehicle_appendix = build_paper_table_per_vehicle_appendix(
      paper_key_facts_by_component,
      paper_key_facts_total,
      paths
    ),
    paper_table_wtw_emissions = build_paper_table_wtw_emissions(energy_emissions, paths),
    paper_table_cost_repricing = build_paper_table_cost_repricing(paper_costs_by_component, paths)
  )

  if (should_build_bev_range_feasibility(paths)) {
    tables$paper_table_bev_range_feasibility <- build_paper_table_bev_range_feasibility(jobs_per_tour, range_logs, paths)
  }

  if (should_build_recharge_vehicle_use(paths)) {
    tables$paper_table_recharge_vehicle_use <- build_paper_table_recharge_vehicle_use(
      selected_plan_cost_legs,
      tour_distances,
      paths
    )
  }

  if (should_build_mixed_ltl_tables(paths)) {
    tables$paper_table_mixed_ltl_fleet_composition <- build_paper_table_mixed_ltl_fleet_composition(
      carrier_time_distance_vehicle_type,
      paths
    )
    tables$paper_table_mixed_ltl_wtw_emissions <- build_paper_table_mixed_ltl_wtw_emissions(
      carrier_time_distance_vehicle_type,
      paths
    )
  }

  tables
}

# Recreates the sample-size table component rows, including Jsprit CPU time from carrier KPIs.
build_paper_table_sample_size_by_component <- function(tour_distances, tour_durations, jobs_per_tour,
                                                       paper_key_facts_by_component, carrier_kpis, paths) {
  small_components <- build_sample_size_small_component_rows(tour_distances, tour_durations, jobs_per_tour)
  non_tour_components <- build_sample_size_non_tour_component_rows(paper_key_facts_by_component, small_components$component)
  cpu_time <- build_component_cpu_time(carrier_kpis)

  rows <- bind_rows(small_components, non_tour_components)
  if (nrow(rows) == 0) {
    return(tibble())
  }

  rows %>%
    left_join(cpu_time, by = "component") %>%
    mutate(
      demand_generation = paths$model_type,
      vehicle_type = paper_vehicle_type_label(paths$fleet_type),
      sample = paste0(paths$sample_tag, "%"),
      agents_tours = round(agents_tours, 0),
      avg_duration_h = round(safe_divide(total_duration_h, agents_tours), 2),
      avg_distance_km = round(safe_divide(total_distance_km, agents_tours), 2),
      avg_stops_per_tour = round(safe_divide(stops_in_sample, agents_tours), 2),
      stops_in_sample = round(stops_in_sample, 0),
      cpu_time_h = round(cpu_time_h, 2),
      source_file = paste_source_columns(source_file, cpu_source_file)
    ) %>%
    arrange(match(component, c(
      "commercialPersonTraffic", "smallScaleGoodsTraffic", "wasteCollection",
      "CEP", "remainingLTL", "FTL", "longDistanceFreight"
    ))) %>%
    transmute(
      scenario_name = paths$scenario_name, demand_generation,
      vehicle_type, sample, row_type = "component", component,
      agents_tours, avg_duration_h, avg_distance_km, avg_stops_per_tour,
      stops_in_sample, cpu_time_h, source_file
    )
}

# Recreates the sample-size paper table with component rows and one total row.
build_paper_table_sample_size <- function(sample_by_component, paths) {
  if (nrow(sample_by_component) == 0) {
    return(tibble())
  }

  total <- sample_by_component %>%
    summarise(
      scenario_name = first(scenario_name),
      demand_generation = first(demand_generation),
      vehicle_type = first(vehicle_type),
      sample = first(sample),
      row_type = "total",
      component = "all components",
      agents_tours_total = safe_sum(agents_tours),
      total_duration_h = safe_sum(.data$avg_duration_h * .data$agents_tours),
      total_distance_km = safe_sum(.data$avg_distance_km * .data$agents_tours),
      stops_in_sample = safe_sum(stops_in_sample),
      cpu_time_h = safe_sum(cpu_time_h),
      source_file = paste(unique(source_file), collapse = ";"),
      .groups = "drop"
    ) %>%
    transmute(
      scenario_name, demand_generation, vehicle_type, sample, row_type, component,
      agents_tours = round(agents_tours_total, 0),
      avg_duration_h = round(safe_divide(total_duration_h, agents_tours_total), 2),
      avg_distance_km = round(safe_divide(total_distance_km, agents_tours_total), 2),
      avg_stops_per_tour = round(safe_divide(stops_in_sample, agents_tours_total), 2),
      stops_in_sample = round(stops_in_sample, 0),
      cpu_time_h = round(cpu_time_h, 2),
      source_file
    )

  bind_rows(sample_by_component, total)
}

# Aggregates tour-analysis summaries for commercialPersonTraffic and smallScaleGoodsTraffic.
build_sample_size_small_component_rows <- function(tour_distances, tour_durations, jobs_per_tour) {
  if (nrow(tour_distances) == 0) {
    return(tibble())
  }

  distance_rows <- tour_distances %>%
    mutate(
      component = paper_component_from_tour_group(groupOfSubpopulation),
      distance_km_value = as_number(distanceInKm)
    ) %>%
    filter(component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic")) %>%
    group_by(component) %>%
    summarise(
      agents_tours = n(),
      total_distance_km = safe_sum(distance_km_value),
      source_file = "analysis/commercialTraffic/tourAnalysis_distances.csv",
      .groups = "drop"
    )

  duration_rows <- tibble(component = character(), total_duration_h = numeric(), duration_source_file = character())
  if (nrow(tour_durations) > 0) {
    duration_rows <- tour_durations %>%
      mutate(
        component = paper_component_from_tour_group(groupOfSubpopulation),
        duration_h_value = as_number(tourDurationsInHours)
      ) %>%
      filter(component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic")) %>%
      group_by(component) %>%
      summarise(
        total_duration_h = safe_sum(duration_h_value),
        duration_source_file = "analysis/commercialTraffic/tourAnalysis_durations.csv",
        .groups = "drop"
      )
  }

  job_rows <- tibble(component = character(), stops_in_sample = numeric(), jobs_source_file = character())
  if (nrow(jobs_per_tour) > 0) {
    job_rows <- jobs_per_tour %>%
      mutate(
        component = paper_component_from_tour_group(groupOfSubpopulation),
        jobs_per_tour_value = as_number(jobsPerTour)
      ) %>%
      filter(component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic")) %>%
      group_by(component) %>%
      summarise(
        stops_in_sample = safe_sum(jobs_per_tour_value),
        jobs_source_file = "analysis/commercialTraffic/tourAnalysis_jobsPerTour.csv",
        .groups = "drop"
      )
  }

  distance_rows %>%
    left_join(duration_rows, by = "component") %>%
    left_join(job_rows, by = "component") %>%
    mutate(
      source_file = paste_source_columns(source_file, duration_source_file, jobs_source_file)
    ) %>%
    select(component, agents_tours, total_distance_km, total_duration_h, stops_in_sample, source_file)
}

# Adds non-tour-analysis components, such as longDistanceFreight and FTL, to sample-size rows.
build_sample_size_non_tour_component_rows <- function(paper_key_facts_by_component, existing_components) {
  if (nrow(paper_key_facts_by_component) == 0) {
    return(tibble())
  }

  if (is.null(existing_components)) {
    existing_components <- character()
  }

  optional_cols <- c(
    "distance_km_inSimulation", "route_distance_km", "cutout_distance_km",
    "travel_time_h_inSimulation", "route_travel_time_h", "cutout_travel_time_h",
    "tour_duration_h_inSimulation", "tour_duration_h"
  )
  for (col in setdiff(optional_cols, names(paper_key_facts_by_component))) {
    paper_key_facts_by_component[[col]] <- NA_real_
  }

  paper_key_facts_by_component %>%
    filter(!component %in% existing_components, component_group != "total") %>%
    mutate(
      sample_size_distance_km = coalesce(distance_km_inSimulation, route_distance_km, cutout_distance_km, distance_km),
      sample_size_duration_h = coalesce(
        tour_duration_h_inSimulation,
        route_travel_time_h,
        cutout_travel_time_h,
        travel_time_h_inSimulation,
        travel_time_h
      )
    ) %>%
    transmute(
      component,
      agents_tours = vehicles,
      total_distance_km = sample_size_distance_km,
      total_duration_h = sample_size_duration_h,
      stops_in_sample = coalesce(trips, tours, vehicles),
      source_file
    )
}

# Extracts Jsprit CPU time by paper component from carrier-level KPI files.
build_component_cpu_time <- function(carrier_kpis) {
  if (nrow(carrier_kpis) == 0) {
    return(tibble(component = character(), cpu_time_h = numeric(), cpu_source_file = character()))
  }

  carrier_kpis %>%
    filter(parameter == "Jsprit CPU Time") %>%
    mutate(
      component = paper_component_from_carrier_component(component),
      cpu_seconds = coalesce(as_number(value_seconds), parse_duration_hms_to_seconds(value))
    ) %>%
    filter(!is.na(component)) %>%
    group_by(component) %>%
    summarise(
      cpu_time_h = safe_sum(cpu_seconds) / 3600,
      cpu_source_file = paste(unique(source_file), collapse = ";"),
      .groups = "drop"
    )
}

# Formats key-facts component and total rows in one paper-facing table.
build_paper_table_key_facts <- function(paper_key_facts_by_component, paper_key_facts_total,
                                        paper_comparable_costs, paths) {
  rows <- bind_rows(
    add_paper_table_row_type(paper_key_facts_by_component, "component"),
    add_paper_table_row_type(paper_key_facts_total, "total")
  )
  if (nrow(rows) == 0) {
    return(tibble())
  }

  rows %>%
    add_comparable_cost_columns(paper_comparable_costs, paths) %>%
    transmute(
      scenario_name,
      demand_generation = model_type,
      vehicle_type = paper_vehicle_type_label(fleet_type),
      sample = paste0(sample_pct, "%"),
      row_type,
      component,
      vehicles = round(vehicles, 0),
      vehicles_100pct = round(vehicles * sample_factor_to_100pct, 0),
      trips = round(trips, 0),
      trips_100pct = round(trips * sample_factor_to_100pct, 0),
      total_distance_traveled_km = round(distance_km_inSimulation, 0),
      total_distance_traveled_km_100pct = round(distance_km_inSimulation * sample_factor_to_100pct, 0),
      total_distance_traveled_km_withBoundary =
        round(with_boundary_only(distance_km_inSimulation, distance_km_withBoundary), 0),
      total_distance_traveled_km_100pct_withBoundary =
        round(with_boundary_only(distance_km_inSimulation, distance_km_withBoundary) * sample_factor_to_100pct, 0),
      total_travel_time_h = round(travel_time_h_inSimulation, 0),
      total_travel_time_h_100pct = round(travel_time_h_inSimulation * sample_factor_to_100pct, 0),
      total_travel_time_h_withBoundary =
        round(with_boundary_only(travel_time_h_inSimulation, travel_time_h_withBoundary), 0),
      total_travel_time_h_100pct_withBoundary =
        round(with_boundary_only(travel_time_h_inSimulation, travel_time_h_withBoundary) * sample_factor_to_100pct, 0),
      total_tour_duration_h = round(tour_duration_h_inSimulation, 0),
      total_tour_duration_h_100pct = round(tour_duration_h_inSimulation * sample_factor_to_100pct, 0),
      total_tour_duration_h_withBoundary =
        round(with_boundary_only(tour_duration_h_inSimulation, tour_duration_h_withBoundary), 0),
      total_tour_duration_h_100pct_withBoundary =
        round(with_boundary_only(tour_duration_h_inSimulation, tour_duration_h_withBoundary) * sample_factor_to_100pct, 0),
      total_cost_eur = round(total_cost_eur_inSimulation, 0),
      total_cost_eur_100pct = round(total_cost_eur_inSimulation * sample_factor_to_100pct, 0),
      total_cost_eur_withBoundary =
        round(with_boundary_only(total_cost_eur_inSimulation, total_cost_eur_withBoundary), 0),
      total_cost_eur_100pct_withBoundary =
        round(with_boundary_only(total_cost_eur_inSimulation, total_cost_eur_withBoundary) * sample_factor_to_100pct, 0),
      included_in_comparable_cost,
      comparable_cost_eur = round(comparable_cost_eur, 0),
      comparable_cost_eur_100pct = round(comparable_cost_eur * sample_factor_to_100pct, 0),
      sample_scope, evaluation_scope, metric_scope, source_file
    )
}

# Formats the per-vehicle appendix table in its final paper columns.
build_paper_table_per_vehicle_appendix <- function(paper_key_facts_by_component, paper_key_facts_total, paths) {
  rows <- bind_rows(
    add_paper_table_row_type(paper_key_facts_by_component, "component"),
    add_paper_table_row_type(paper_key_facts_total, "total")
  )
  if (nrow(rows) == 0) {
    return(tibble())
  }

  rows %>%
    transmute(
      scenario_name,
      demand_generation = model_type,
      vehicle_type = paper_vehicle_type_label(fleet_type),
      sample = paste0(sample_pct, "%"),
      row_type,
      component,
      vehicles = round(vehicles, 0),
      trips = round(trips, 0),
      reported_distance_km = round(distance_km_inSimulation, 0),
      reported_distance_km_withBoundary =
        round(with_boundary_only(distance_km_inSimulation, distance_km_withBoundary), 0),
      reported_travel_time_h = round(travel_time_h_inSimulation, 0),
      reported_travel_time_h_withBoundary =
        round(with_boundary_only(travel_time_h_inSimulation, travel_time_h_withBoundary), 0),
      reported_tour_duration_h = round(tour_duration_h_inSimulation, 0),
      reported_tour_duration_h_withBoundary =
        round(with_boundary_only(tour_duration_h_inSimulation, tour_duration_h_withBoundary), 0),
      avg_distance_km_per_vehicle = round(avg_distance_km_inSimulation_per_vehicle, 2),
      avg_distance_km_withBoundary_per_vehicle =
        round(with_boundary_only(avg_distance_km_inSimulation_per_vehicle, avg_distance_km_withBoundary_per_vehicle), 2),
      avg_travel_time_h_per_vehicle = round(avg_travel_time_h_inSimulation_per_vehicle, 2),
      avg_travel_time_h_withBoundary_per_vehicle =
        round(with_boundary_only(avg_travel_time_h_inSimulation_per_vehicle, avg_travel_time_h_withBoundary_per_vehicle), 2),
      avg_tour_duration_h_per_vehicle = round(avg_tour_duration_h_inSimulation_per_vehicle, 2),
      avg_tour_duration_h_withBoundary_per_vehicle =
        round(with_boundary_only(avg_tour_duration_h_inSimulation_per_vehicle, avg_tour_duration_h_withBoundary_per_vehicle), 2),
      sample_scope, evaluation_scope, metric_scope, source_file
    )
}

# Formats single-run WTW emissions by component and total in one appendix-style table.
build_paper_table_wtw_emissions <- function(energy_emissions, paths) {
  if (nrow(energy_emissions) == 0) {
    return(tibble())
  }

  emission_rows <- energy_emissions %>%
    mutate(component = paper_component_from_emission_group(groupOfSubpopulation)) %>%
    filter(should_build_ltl_analyses(paths) | component != "LTL")
  if (nrow(emission_rows) == 0) {
    return(tibble())
  }

  by_component <- emission_rows %>%
    group_by(component, emission_factor_year) %>%
    summarise(
      scenario_name = first(scenario_name),
      demand_generation = first(model_type),
      vehicle_type = paper_vehicle_type_label(first(fleet_type)),
      scenario_year = first(year),
      row_type = "component",
      vehicle_km_million_day = safe_sum(vehicle_km_sample_day) / 1e6,
      gasoline_million_l_year = safe_sum(ifelse(powertrain == "gasoline", energy_amount_sample_year, NA_real_)) / 1e6,
      diesel_million_l_year = safe_sum(ifelse(powertrain == "diesel", energy_amount_sample_year, NA_real_)) / 1e6,
      electricity_gwh_year = safe_sum(ifelse(powertrain == "BEV", energy_amount_sample_year, NA_real_)) / 1e6,
      wtw_emissions_kt_year = safe_sum(emission_t_co2e_sample_year) / 1000,
      source_file = paste(unique(source_file), collapse = ";"),
      .groups = "drop"
    )

  total <- emission_rows %>%
    group_by(emission_factor_year) %>%
    summarise(
      scenario_name = first(scenario_name),
      demand_generation = first(model_type),
      vehicle_type = paper_vehicle_type_label(first(fleet_type)),
      scenario_year = first(year),
      row_type = "total",
      component = "all components",
      vehicle_km_million_day = safe_sum(vehicle_km_sample_day) / 1e6,
      gasoline_million_l_year = safe_sum(ifelse(powertrain == "gasoline", energy_amount_sample_year, NA_real_)) / 1e6,
      diesel_million_l_year = safe_sum(ifelse(powertrain == "diesel", energy_amount_sample_year, NA_real_)) / 1e6,
      electricity_gwh_year = safe_sum(ifelse(powertrain == "BEV", energy_amount_sample_year, NA_real_)) / 1e6,
      wtw_emissions_kt_year = safe_sum(emission_t_co2e_sample_year) / 1000,
      source_file = paste(unique(source_file), collapse = ";"),
      .groups = "drop"
    )

  bind_rows(by_component, total) %>%
    mutate(
      vehicle_km_million_day = round(vehicle_km_million_day, 3),
      gasoline_million_l_year = round(gasoline_million_l_year, 1),
      diesel_million_l_year = round(diesel_million_l_year, 1),
      electricity_gwh_year = round(electricity_gwh_year, 1),
      wtw_emissions_kt_year = round(wtw_emissions_kt_year, 1)
    ) %>%
    transmute(
      scenario_name, demand_generation, vehicle_type, scenario_year, emission_factor_year,
      row_type, component, vehicle_km_million_day, gasoline_million_l_year,
      diesel_million_l_year, electricity_gwh_year, wtw_emissions_kt_year,
      source_file
    )
}

# Formats component and total cost rows for run-year and alternate price-year settings.
build_paper_table_cost_repricing <- function(paper_costs_by_component, paths) {
  if (is.null(paper_costs_by_component) || nrow(paper_costs_by_component) == 0) {
    return(tibble())
  }

  paper_costs_by_component %>%
    transmute(
      scenario_name,
      demand_generation = model_type,
      vehicle_type = paper_vehicle_type_label(fleet_type),
      sample = paste0(sample_pct, "%"),
      scenario_year = year,
      cost_result_year,
      row_type,
      component,
      vehicles = round(vehicles, 0),
      total_distance_traveled_km = round(route_distance_km, 0),
      total_distance_traveled_km_withBoundary =
        round(with_boundary_only(route_distance_km, distance_km), 0),
      total_travel_time_h = round(route_travel_time_h, 0),
      total_travel_time_h_withBoundary =
        round(with_boundary_only(route_travel_time_h, travel_time_h), 0),
      activity_time_h = round(activity_time_h, 0),
      fixed_cost_eur = round(fixed_cost_eur, 0),
      distance_cost_eur = round(distance_cost_eur, 0),
      distance_cost_eur_withBoundary =
        round(with_boundary_only(distance_cost_eur, distance_cost_eur + boundary_distance_cost_eur), 0),
      travel_time_cost_eur = round(time_cost_eur, 0),
      travel_time_cost_eur_withBoundary =
        round(with_boundary_only(time_cost_eur, time_cost_eur + boundary_time_cost_eur), 0),
      activity_time_cost_eur = round(activity_time_cost_eur, 0),
      time_cost_eur = round(time_cost_eur + activity_time_cost_eur, 0),
      time_cost_eur_withBoundary =
        round(with_boundary_only(time_cost_eur + activity_time_cost_eur, time_cost_eur + boundary_time_cost_eur + activity_time_cost_eur), 0),
      total_cost_eur = round(total_cost_eur - boundary_distance_cost_eur - boundary_time_cost_eur, 0),
      total_cost_eur_withBoundary =
        round(with_boundary_only(total_cost_eur - boundary_distance_cost_eur - boundary_time_cost_eur, total_cost_eur), 0),
      included_in_comparable_cost,
      comparable_cost_eur = round(comparable_cost_eur, 0),
      source_file
    )
}

add_comparable_cost_columns <- function(rows, paper_comparable_costs, paths) {
  if (nrow(rows) == 0) {
    return(rows)
  }

  comparable_lookup <- tibble()
  comparable_total <- NA_real_
  if (!is.null(paper_comparable_costs) && nrow(paper_comparable_costs) > 0) {
    comparable_lookup <- paper_comparable_costs %>%
      transmute(
        component,
        comparable_lookup_included = included_in_comparable_cost,
        comparable_lookup_cost = comparable_cost_eur
      )

    comparable_total <- paper_comparable_costs %>%
      filter(component == "comparable cost total") %>%
      pull(comparable_cost_eur) %>%
      first()
  }

  rows %>%
    left_join(comparable_lookup, by = "component") %>%
    mutate(
      component_excluded = component %in% comparable_cost_excluded_components(paths$model_type),
      included_in_comparable_cost = case_when(
        !is.na(comparable_lookup_included) ~ comparable_lookup_included,
        component == "long-distance total" ~ FALSE,
        TRUE ~ !component_excluded
      ),
      comparable_cost_eur = case_when(
        !is.na(comparable_lookup_cost) ~ comparable_lookup_cost,
        component == "all components" & !is.na(comparable_total) ~ comparable_total,
        included_in_comparable_cost ~ total_cost_eur,
        TRUE ~ 0
      )
    ) %>%
    select(-comparable_lookup_included, -comparable_lookup_cost, -component_excluded)
}

build_paper_table_bev_range_feasibility <- function(jobs_per_tour, range_logs, paths) {
  if (!should_build_bev_range_feasibility(paths) || nrow(jobs_per_tour) == 0) {
    return(tibble())
  }

  job_rows <- jobs_per_tour %>%
    mutate(
      component = paper_component_from_tour_group(groupOfSubpopulation),
      jobs_per_tour_value = as_number(jobsPerTour)
    ) %>%
    filter(component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic")) %>%
    group_by(component) %>%
    summarise(
      jobs_shipments = safe_sum(jobs_per_tour_value),
      jobs_source_file = "analysis/commercialTraffic/tourAnalysis_jobsPerTour.csv",
      .groups = "drop"
    )
  if (nrow(job_rows) == 0) {
    return(tibble())
  }

  range_rows <- tibble(
    component = character(),
    agents_in_study_area_start_scope = numeric(),
    jobs_not_feasible_without_recharging = numeric(),
    any_recharge_evaluation_services = numeric(),
    selected_recharge_fallback_services = numeric(),
    range_infeasible_selected_recharge_services = numeric(),
    near_limit_selected_recharge_services = numeric(),
    services_without_feasible_recharge_fallback = numeric(),
    services_without_feasible_recharge_fallback_also_later_selected = numeric(),
    recharge_vehicles_added = numeric(),
    range_source_file = character()
  )
  if (nrow(range_logs) > 0) {
    range_rows <- range_logs %>%
      filter(component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic")) %>%
      group_by(component) %>%
      summarise(
        agents_in_study_area_start_scope = safe_max(agents_in_study_area_start_scope),
        any_recharge_evaluation_services = safe_sum(any_recharge_evaluation_services),
        selected_recharge_fallback_services = safe_sum(selected_recharge_fallback_services),
        range_infeasible_selected_recharge_services = safe_sum(range_infeasible_selected_recharge_services),
        near_limit_selected_recharge_services = safe_sum(near_limit_selected_recharge_services),
        services_without_feasible_recharge_fallback = safe_sum(services_without_feasible_recharge_fallback),
        services_without_feasible_recharge_fallback_also_later_selected =
          safe_sum(services_without_feasible_recharge_fallback_also_later_selected),
        recharge_vehicles_added = safe_sum(added_high_cost_recharge_vehicles),
        range_source_file = collapse_source_values(log_file),
        .groups = "drop"
      ) %>%
      mutate(
        jobs_not_feasible_without_recharging = selected_recharge_fallback_services
      )
  }

  component_rows <- job_rows %>%
    left_join(range_rows, by = "component") %>%
    mutate(
      agents_in_study_area_start_scope = coalesce(agents_in_study_area_start_scope, 0),
      any_recharge_evaluation_services = coalesce(any_recharge_evaluation_services, 0),
      jobs_not_feasible_without_recharging = coalesce(jobs_not_feasible_without_recharging, 0),
      selected_recharge_fallback_services = coalesce(selected_recharge_fallback_services, 0),
      range_infeasible_selected_recharge_services = coalesce(range_infeasible_selected_recharge_services, 0),
      near_limit_selected_recharge_services = coalesce(near_limit_selected_recharge_services, 0),
      services_without_feasible_recharge_fallback = coalesce(services_without_feasible_recharge_fallback, 0),
      services_without_feasible_recharge_fallback_also_later_selected =
        coalesce(services_without_feasible_recharge_fallback_also_later_selected, 0),
      recharge_vehicles_added = coalesce(recharge_vehicles_added, 0),
      share_not_feasible_without_recharging = safe_divide(jobs_not_feasible_without_recharging, jobs_shipments),
      row_type = "component",
      source_file = paste_source_columns(jobs_source_file, range_source_file)
    )

  total <- component_rows %>%
    summarise(
      component = "reported subtotal",
      agents_in_study_area_start_scope = safe_sum(agents_in_study_area_start_scope),
      any_recharge_evaluation_services = safe_sum(any_recharge_evaluation_services),
      jobs_shipments = safe_sum(jobs_shipments),
      jobs_not_feasible_without_recharging = safe_sum(jobs_not_feasible_without_recharging),
      selected_recharge_fallback_services = safe_sum(selected_recharge_fallback_services),
      range_infeasible_selected_recharge_services = safe_sum(range_infeasible_selected_recharge_services),
      near_limit_selected_recharge_services = safe_sum(near_limit_selected_recharge_services),
      services_without_feasible_recharge_fallback = safe_sum(services_without_feasible_recharge_fallback),
      services_without_feasible_recharge_fallback_also_later_selected =
        safe_sum(services_without_feasible_recharge_fallback_also_later_selected),
      recharge_vehicles_added = safe_sum(recharge_vehicles_added),
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    mutate(
      share_not_feasible_without_recharging = safe_divide(jobs_not_feasible_without_recharging, jobs_shipments),
      row_type = "total"
    )

  bind_rows(component_rows, total) %>%
    transmute(
      scenario_name = paths$scenario_name,
      demand_generation = paths$model_type,
      vehicle_type = paper_vehicle_type_label(paths$fleet_type),
      sample = paste0(paths$sample_tag, "%"),
      row_type,
      component,
      agents_in_study_area_start_scope = round(agents_in_study_area_start_scope, 0),
      jobs_shipments = round(jobs_shipments, 0),
      jobs_not_feasible_without_recharging = round(jobs_not_feasible_without_recharging, 0),
      any_recharge_evaluation_services = round(any_recharge_evaluation_services, 0),
      selected_recharge_fallback_services = round(selected_recharge_fallback_services, 0),
      range_infeasible_selected_recharge_services = round(range_infeasible_selected_recharge_services, 0),
      near_limit_selected_recharge_services = round(near_limit_selected_recharge_services, 0),
      services_without_feasible_recharge_fallback = round(services_without_feasible_recharge_fallback, 0),
      services_without_feasible_recharge_fallback_also_later_selected =
        round(services_without_feasible_recharge_fallback_also_later_selected, 0),
      share_not_feasible_without_recharging = round(share_not_feasible_without_recharging, 4),
      recharge_vehicles_added = round(recharge_vehicles_added, 0),
      source_file
    )
}

build_paper_table_recharge_vehicle_use <- function(selected_plan_cost_legs, tour_distances, paths) {
  if (!should_build_recharge_vehicle_use(paths) || nrow(selected_plan_cost_legs) == 0) {
    return(tibble())
  }

  required <- c("person", "leg_vehicle_type", "paper_component", "source_role", "selected_plan_source_file")
  missing <- setdiff(required, names(selected_plan_cost_legs))
  if (length(missing) > 0) {
    stop("Selected-plan leg rows miss required columns for recharge table: ", paste(missing, collapse = ", "), call. = FALSE)
  }

  component_order <- c(
    "commercialPersonTraffic", "smallScaleGoodsTraffic",
    "wasteCollection", "CEP", "remainingLTL",
    "FTL", "longDistanceFreight"
  )

  study_area_start_scope <- build_study_area_start_person_scope(tour_distances)

  rows <- selected_plan_cost_legs %>%
    filter(
      source_role == "cutout_route",
      !is.na(person),
      nzchar(person),
      !is.na(leg_vehicle_type),
      nzchar(leg_vehicle_type),
      paper_component %in% component_order
    ) %>%
    left_join(
      study_area_start_scope %>% mutate(in_study_area_start_scope = TRUE),
      by = c("paper_component" = "component", "person" = "person")
    ) %>%
    mutate(
      in_study_area_start_scope = coalesce(in_study_area_start_scope, FALSE),
      include_in_recharge_vehicle_scope = case_when(
        paper_component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic") ~
          in_study_area_start_scope,
        paper_component %in% c("wasteCollection", "CEP", "remainingLTL", "FTL", "longDistanceFreight") ~
          TRUE,
        TRUE ~ FALSE
      )
    ) %>%
    filter(include_in_recharge_vehicle_scope) %>%
    group_by(person) %>%
    summarise(
      component = first_non_missing(paper_component),
      assigned_vehicle_type = {
        vehicle_types <- unique(as.character(leg_vehicle_type))
        recharge_types <- vehicle_types[is_recharge_vehicle_type(vehicle_types)]
        if (length(recharge_types) > 0) recharge_types[[1]] else first_non_missing(vehicle_types)
      },
      uses_recharge_vehicle = any(is_recharge_vehicle_type(leg_vehicle_type), na.rm = TRUE),
      selected_plan_source_file = collapse_source_values(selected_plan_source_file),
      .groups = "drop"
    ) %>%
    mutate(
      component = factor(component, levels = component_order, ordered = TRUE)
    )

  if (nrow(rows) == 0) {
    return(tibble())
  }

  component_rows <- rows %>%
    group_by(component) %>%
    summarise(
      assigned_agents = n_distinct(person),
      recharge_agents = n_distinct(person[uses_recharge_vehicle]),
      recharge_vehicle_types = collapse_source_values(unique(assigned_vehicle_type[uses_recharge_vehicle])),
      source_file = collapse_source_values(selected_plan_source_file),
      .groups = "drop"
    ) %>%
    mutate(row_type = "component")

  total <- component_rows %>%
    summarise(
      component = factor("all assigned components", levels = c(component_order, "all assigned components"), ordered = TRUE),
      assigned_agents = safe_sum(assigned_agents),
      recharge_agents = safe_sum(recharge_agents),
      recharge_vehicle_types = collapse_source_values(recharge_vehicle_types),
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    mutate(row_type = "total")

  bind_rows(component_rows, total) %>%
    mutate(
      recharge_agent_share = safe_divide(recharge_agents, assigned_agents),
      component = as.character(component)
    ) %>%
    arrange(match(component, c(component_order, "all assigned components"))) %>%
    transmute(
      scenario_name = paths$scenario_name,
      demand_generation = paths$model_type,
      vehicle_type = paper_vehicle_type_label(paths$fleet_type),
      sample = paste0(paths$sample_tag, "%"),
      row_type,
      component,
      assigned_agents = round(assigned_agents, 0),
      recharge_agents = round(recharge_agents, 0),
      recharge_agent_share = round(recharge_agent_share, 4),
      recharge_vehicle_types,
      source_file = "output_plans.xml.zst selected-plan vehicleTypes"
    )
}

build_paper_table_mixed_ltl_fleet_composition <- function(carrier_time_distance_vehicle_type, paths) {
  if (!should_build_mixed_ltl_tables(paths)) {
    return(tibble())
  }

  ltl_rows <- prepare_mixed_ltl_vehicle_type_rows(carrier_time_distance_vehicle_type, paths)
  if (nrow(ltl_rows) == 0) {
    return(tibble())
  }

  component_rows <- ltl_rows %>%
    group_by(component) %>%
    summarise(
      vehicles = safe_sum(vehicles),
      bev_vehicles = safe_sum(ifelse(powertrain == "BEV", vehicles, 0)),
      total_distance_km = safe_sum(total_distance_km),
      bev_distance_km = safe_sum(ifelse(powertrain == "BEV", total_distance_km, 0)),
      total_cost_eur = safe_sum(total_cost_eur),
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    mutate(row_type = "component")

  total <- component_rows %>%
    summarise(
      component = "Total",
      vehicles = safe_sum(vehicles),
      bev_vehicles = safe_sum(bev_vehicles),
      total_distance_km = safe_sum(total_distance_km),
      bev_distance_km = safe_sum(bev_distance_km),
      total_cost_eur = safe_sum(total_cost_eur),
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    mutate(row_type = "total")

  bind_rows(component_rows, total) %>%
    mutate(
      bev_vehicle_share = safe_divide(bev_vehicles, vehicles),
      bev_distance_share = safe_divide(bev_distance_km, total_distance_km)
    ) %>%
    transmute(
      scenario_name = paths$scenario_name,
      demand_generation = paths$model_type,
      vehicle_type = paper_vehicle_type_label(paths$fleet_type),
      price_setting = paths$year,
      sample = paste0(paths$sample_tag, "%"),
      row_type,
      component,
      vehicles = round(vehicles, 0),
      bev_vehicles = round(bev_vehicles, 0),
      bev_vehicle_share = round(bev_vehicle_share, 4),
      total_distance_km = round(total_distance_km, 0),
      bev_distance_share = round(bev_distance_share, 4),
      total_cost_eur = round(total_cost_eur, 0),
      source_file
    )
}

build_paper_table_mixed_ltl_wtw_emissions <- function(carrier_time_distance_vehicle_type, paths) {
  if (!should_build_mixed_ltl_tables(paths)) {
    return(tibble())
  }

  ltl_rows <- prepare_mixed_ltl_vehicle_type_rows(carrier_time_distance_vehicle_type, paths)
  if (nrow(ltl_rows) == 0) {
    return(tibble())
  }

  factor_years <- validate_emission_factor_years(emission_factor_years)

  emissions <- ltl_rows %>%
    expand_grid(emission_factor_year = factor_years) %>%
    mutate(
      intensity_kg_co2e_per_100km = case_when(
        emission_factor_year == 2021L ~ intensity_kg_co2e_per_100km_2021,
        emission_factor_year == 2030L ~ intensity_kg_co2e_per_100km_2030,
        emission_factor_year == 2050L ~ intensity_kg_co2e_per_100km_2050
      ),
      wtw_emissions_kt_year = total_distance_km * workdays_per_year * intensity_kg_co2e_per_100km / 100 / 1000
    )

  component_rows <- emissions %>%
    group_by(component, emission_factor_year) %>%
    summarise(
      wtw_emissions_kt_year = safe_sum(wtw_emissions_kt_year),
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    mutate(row_type = "component")

  total <- component_rows %>%
    group_by(emission_factor_year) %>%
    summarise(
      component = "Full LTL",
      wtw_emissions_kt_year = safe_sum(wtw_emissions_kt_year),
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    mutate(row_type = "total")

  bind_rows(component_rows, total) %>%
    transmute(
      scenario_name = paths$scenario_name,
      demand_generation = paths$model_type,
      vehicle_type = paper_vehicle_type_label(paths$fleet_type),
      price_setting = paths$year,
      emission_factor_year,
      sample = paste0(paths$sample_tag, "%"),
      row_type,
      component,
      wtw_emissions_kt_year = round(wtw_emissions_kt_year, 1),
      source_file
    )
}

prepare_mixed_ltl_vehicle_type_rows <- function(carrier_time_distance_vehicle_type, paths) {
  if (!should_build_mixed_ltl_tables(paths) || nrow(carrier_time_distance_vehicle_type) == 0) {
    return(tibble())
  }

  carrier_time_distance_vehicle_type %>%
    mutate(
      component = paper_component_from_carrier_component(component),
      vehicles = as_number(nuofvehicles),
      total_distance_km = as_number(.data$sumoftraveldistances_km),
      total_cost_eur = as_number(.data$totalcosts_eur)
    ) %>%
    filter(component %in% ltl_components()) %>%
    left_join(vehicle_type_to_key, by = "vehicle_type_base") %>%
    left_join(vehicle_intensities, by = "vehicle_key")
}

# Adds a row type to component or total rows while preserving empty inputs.
add_paper_table_row_type <- function(data, row_type) {
  if (is.null(data) || nrow(data) == 0) {
    return(tibble())
  }

  data %>% mutate(row_type = row_type)
}

# Maps carrier-analysis component labels to the paper component names.
paper_component_from_carrier_component <- function(component) {
  normalized <- as.character(component)

  case_when(
    normalized == "commercialPersonTraffic" ~ "commercialPersonTraffic",
    normalized %in% c("goodsTraffic", "smallScaleGoodsTraffic") ~ "smallScaleGoodsTraffic",
    normalized %in% c("LTL_Waste", "wasteCollection") ~ "wasteCollection",
    normalized %in% c("LTL_Parcel", "CEP") ~ "CEP",
    normalized %in% c("LTL_Rest", "remainingLTL") ~ "remainingLTL",
    normalized == "FTL" ~ "FTL",
    normalized == "longDistanceFreight" ~ "longDistanceFreight",
    TRUE ~ normalized
  )
}

# Returns the vehicle-type label used in the paper tables.
paper_vehicle_type_label <- function(fleet_type) {
  recode(as.character(fleet_type), CV = "ICEV", EV = "BEV", Mixed = "Mixed", .default = as.character(fleet_type))
}

# Collapses several optional source-file columns into one semicolon-separated value.
paste_source_columns <- function(...) {
  purrr::pmap_chr(list(...), function(...) {
    values <- unlist(list(...), use.names = FALSE)
    collapse_source_values(values)
  })
}

# Collapses one or more semicolon-separated source-file fields without duplicates.
collapse_source_values <- function(values) {
  values <- values[!is.na(values) & nzchar(values)]
  if (length(values) == 0) {
    return(NA_character_)
  }

  paste(unique(unlist(strsplit(values, ";", fixed = TRUE), use.names = FALSE)), collapse = ";")
}

# Documents which components are included in comparable-cost comparisons.
build_paper_comparable_costs <- function(paper_key_facts_by_component, paths) {
  if (nrow(paper_key_facts_by_component) == 0) {
    return(tibble())
  }

  excluded_components <- comparable_cost_excluded_components(paths$model_type)

  component_rows <- paper_key_facts_by_component %>%
    filter(component_group != "total") %>%
    mutate(
      included_in_comparable_cost = !component %in% excluded_components,
      comparable_cost_eur = ifelse(included_in_comparable_cost, total_cost_eur, 0),
      paper_relevance = "paper_comparable_costs"
    )

  total <- component_rows %>%
    filter(included_in_comparable_cost) %>%
    summarise(
      scenario_name = first(scenario_name),
      year = first(year),
      fleet_type = first(fleet_type),
      model_type = first(model_type),
      sample_pct = first(sample_pct),
      sample_factor_to_100pct = first(sample_factor_to_100pct),
      final_run_dir = first(final_run_dir),
      component_group = "total",
      component = "comparable cost total",
      total_cost_eur = safe_sum(total_cost_eur),
      included_in_comparable_cost = TRUE,
      comparable_cost_eur = safe_sum(comparable_cost_eur),
      paper_relevance = "paper_comparable_costs",
      source_file = paste(unique(source_file), collapse = ";"),
      .groups = "drop"
    )

  bind_rows(component_rows, total)
}

# Returns the long-distance components excluded from comparable-cost analyses.
comparable_cost_excluded_components <- function(selected_model_type) {
  case_when(
    selected_model_type == "Basic" ~ "longDistanceFreight",
    selected_model_type == "Advanced" ~ "FTL;longDistanceFreight",
    TRUE ~ ""
  ) %>%
    strsplit(";", fixed = TRUE) %>%
    unlist(use.names = FALSE) %>%
    discard(~ .x == "")
}

extract_vehicle_type_values <- function(vehicle_types_raw) {
  if (is.na(vehicle_types_raw) || trimws(vehicle_types_raw) == "") {
    return("")
  }

  matches <- gregexpr(':"([^"]+)"', vehicle_types_raw, perl = TRUE)
  values <- regmatches(vehicle_types_raw, matches)[[1]]

  if (length(values) == 0 || identical(values, character(0)) || values[[1]] == "-1") {
    return(vehicle_types_raw)
  }

  paste(unique(sub('^:"([^"]+)"$', "\\1", values)), collapse = ";")
}

# Parses the MATSim PersonVehicleTypes JSON-style map, e.g. {"car":"VW_T6"}.
parse_vehicle_type_map <- function(vehicle_types_raw) {
  if (is.na(vehicle_types_raw) || trimws(vehicle_types_raw) == "") {
    return(setNames(character(), character()))
  }

  matches <- stringr::str_match_all(vehicle_types_raw, '"([^"]+)"\\s*:\\s*"([^"]+)"')[[1]]
  if (nrow(matches) == 0) {
    return(setNames(character(), character()))
  }

  values <- matches[, 3]
  names(values) <- matches[, 2]
  values
}

# Resolves the vehicle type that MATSim scoring uses for one leg mode.
resolve_vehicle_type_for_leg <- function(leg_mode, vehicle_types_raw, vehicle_type_values) {
  vehicle_map <- parse_vehicle_type_map(vehicle_types_raw)
  if (length(vehicle_map) > 0) {
    if (!is.na(leg_mode) && leg_mode %in% names(vehicle_map)) {
      return(unname(vehicle_map[[leg_mode]]))
    }
    if (length(vehicle_map) == 1) {
      return(unname(vehicle_map[[1]]))
    }
  }

  values <- unlist(strsplit(as.character(vehicle_type_values), ";", fixed = TRUE), use.names = FALSE)
  values <- trimws(values[!is.na(values) & nzchar(values)])
  if (length(values) == 1) {
    return(values[[1]])
  }

  if (!is.na(leg_mode) && nzchar(leg_mode)) {
    return(leg_mode)
  }

  NA_character_
}

resolve_emission_vehicle_type_base <- function(vehicle_type_base, paths) {
  resolved <- as.character(vehicle_type_base)
  if (is_bev_fleet(paths)) {
    resolved <- ifelse(!is.na(resolved) & resolved == "heavy40t", "heavy40t_EV", resolved)
  }

  resolved
}

# =============================================================================
# 6. Fahrzeugparameter und WTW-Emissionsschaetzung
# =============================================================================

# Finds the vehicle-type XML in the expected price-year run folder, unless an override is configured.
find_vehicle_types_file <- function(paths, cost_year = paths$year) {
  cost_year <- as.integer(cost_year)
  override <- cost_vehicle_type_file_override(cost_year, paths)
  if (!is.na(override)) {
    return(override)
  }

  scenario_name_for_cost_year <- sub("_[0-9]{4}$", paste0("_", cost_year), paths$scenario_name)
  vehicle_file <- file.path(
    paths$study_dir,
    scenario_name_for_cost_year,
    basename(paths$sample_dir),
    "mode-vehicles_withKwmCarrierVehicleTypes.xml.gz"
  )
  if (!file.exists(vehicle_file)) {
    return(NA_character_)
  }

  normalizePath(vehicle_file, winslash = "/", mustWork = TRUE)
}

# Returns an explicit price-year vehicle-type file from the manual override block, if configured.
cost_vehicle_type_file_override <- function(cost_year, paths) {
  if (!exists("cost_vehicle_type_file_overrides", inherits = TRUE)) {
    return(NA_character_)
  }

  overrides <- get("cost_vehicle_type_file_overrides", inherits = TRUE)
  if (length(overrides) == 0 || is.null(names(overrides))) {
    return(NA_character_)
  }

  override_name <- as.character(as.integer(cost_year))
  if (!override_name %in% names(overrides)) {
    return(NA_character_)
  }

  selected <- overrides[[override_name]]
  if (is.null(selected) || is.na(selected) || !nzchar(selected)) {
    return(NA_character_)
  }

  selected <- normalize_user_path(selected)
  if (!grepl("^[A-Za-z]:/", selected) && !startsWith(selected, "/")) {
    selected <- file.path(paths$study_dir, selected)
  }
  if (!file.exists(selected)) {
    add_warning("Configured cost vehicle-type override does not exist for ", cost_year, ": ", selected)
    return(NA_character_)
  }

  normalizePath(selected, winslash = "/", mustWork = TRUE)
}

# Reads vehicle-type parameters and annotates them with the price year used for costs.
read_vehicle_parameters_for_cost_year <- function(paths, cost_year) {
  vehicle_file <- find_vehicle_types_file(paths, cost_year)
  if (is.na(vehicle_file)) {
    add_warning("No vehicle types XML found for cost year ", cost_year, " and run: ", paths$sample_dir)
    return(tibble())
  }

  blocks <- read_vehicle_type_blocks(vehicle_file)
  if (length(blocks) == 0) {
    add_warning("No vehicleType blocks found in: ", vehicle_file)
    return(tibble())
  }

  purrr::map_dfr(blocks, parse_vehicle_type_block) %>%
    mutate(
      cost_result_year = as.integer(cost_year),
      source_file = vehicle_file
    ) %>%
    with_meta(paths, basename(vehicle_file))
}

# Reads the vehicle-type cost rates for all requested price years.
read_cost_vehicle_parameters <- function(paths, selected_cost_years) {
  selected_cost_years <- unique(as.integer(selected_cost_years))
  purrr::map_dfr(selected_cost_years, ~ read_vehicle_parameters_for_cost_year(paths, .x))
}

read_vehicle_type_blocks <- function(vehicle_file) {
  con <- open_text_connection(vehicle_file)
  on.exit(close(con), add = TRUE)

  blocks <- list()
  current <- character()
  inside <- FALSE

  repeat {
    lines <- readLines(con, n = 5000, warn = FALSE)
    if (length(lines) == 0) {
      break
    }

    for (line in lines) {
      if (grepl("<vehicleType ", line, fixed = TRUE)) {
        inside <- TRUE
        current <- line
      } else if (inside) {
        current <- c(current, line)
      }

      if (inside && grepl("</vehicleType>", line, fixed = TRUE)) {
        blocks[[length(blocks) + 1L]] <- current
        current <- character()
        inside <- FALSE
      }
    }
  }

  blocks
}

parse_vehicle_type_block <- function(block) {
  text <- paste(block, collapse = "\n")

  tibble(
    vehicle_type = extract_attr_from_text(text, "vehicleType", "id"),
    fixed_costs_per_day = as_number(extract_attr_from_text(text, "costInformation", "fixedCostsPerDay")),
    costs_per_meter = as_number(extract_attr_from_text(text, "costInformation", "costsPerMeter")),
    costs_per_second = as_number(extract_attr_from_text(text, "costInformation", "costsPerSecond"))
  )
}

extract_attr_from_text <- function(text, tag, attr) {
  pattern <- paste0("<", tag, "[^>]*\\s", attr, '="([^"]*)"')
  match <- str_match(text, pattern)[, 2]
  ifelse(is.na(match), NA_character_, match)
}

vehicle_intensities <- tibble(
  vehicle_key = c(
    "passenger_car_gasoline", "passenger_car_bev",
    "ldv_upto_2.8t_diesel", "ldv_upto_2.8t_bev",
    "ldv_2.8_to_3.5t_diesel", "ldv_2.8_to_3.5t_bev",
    "truck_7.5t_diesel", "truck_7.5t_bev_fuso", "truck_7.5t_bev_quantron",
    "truck_18t_diesel", "truck_18t_bev",
    "waste_25t_diesel", "waste_25t_bev_large", "waste_25t_bev_small",
    "hgv_40t_diesel", "hgv_40t_bev"
  ),
  vehicle_class = c(
    "Passenger car", "Passenger car",
    "LDV to 2.8 t", "LDV to 2.8 t",
    "LDV 2.8 to 3.5 t", "LDV 2.8 to 3.5 t",
    "Truck 7.5 t", "Truck 7.5 t", "Truck 7.5 t",
    "Truck 18 t", "Truck 18 t",
    "Waste collection vehicle 25 t", "Waste collection vehicle 25 t", "Waste collection vehicle 25 t",
    "HGV 40 t", "HGV 40 t"
  ),
  powertrain = c(
    "gasoline", "BEV",
    "diesel", "BEV",
    "diesel", "BEV",
    "diesel", "BEV", "BEV",
    "diesel", "BEV",
    "diesel", "BEV", "BEV",
    "diesel", "BEV"
  ),
  energy_use_per_100km = c(
    5.40, 15.50, 10.50, 19.20, 11.00, 28.70, 15.50, 85.00, 85.00,
    26.50, 100.00, 60.00, 100.00, 100.00, 32.00, 150.00
  ),
  intensity_kg_co2e_per_100km_2021 = c(
    15.55, 7.60, 33.29, 9.41, 34.87, 14.06, 49.14, 41.65, 41.65,
    84.01, 49.00, 190.20, 49.00, 49.00, 101.44, 73.50
  ),
  intensity_kg_co2e_per_100km_2030 = c(
    15.55, 3.89, 33.29, 4.82, 34.87, 7.20, 49.14, 21.34, 21.34,
    84.01, 25.10, 190.20, 25.10, 25.10, 101.44, 37.65
  ),
  intensity_kg_co2e_per_100km_2050 = c(
    15.55, 1.46, 33.29, 1.80, 34.87, 2.70, 49.14, 7.99, 7.99,
    84.01, 9.40, 190.20, 9.40, 9.40, 101.44, 14.10
  )
)

vehicle_type_to_key <- tibble(
  vehicle_type_base = c(
    "golf1.0", "ID.3", "VW_T6", "ID.Buzz", "mercedes316", "mercedesESprinter",
    "light8t", "light8t_EV", "light8t_EV2",
    "medium18t", "medium18t_parcel", "medium18t_EV", "medium18t_parcel_EV",
    "waste_collection_diesel", "waste_collection_EV1", "waste_collection_EV2",
    "heavy40t", "heavy40t_EV"
  ),
  vehicle_key = c(
    "passenger_car_gasoline", "passenger_car_bev",
    "ldv_upto_2.8t_diesel", "ldv_upto_2.8t_bev",
    "ldv_2.8_to_3.5t_diesel", "ldv_2.8_to_3.5t_bev",
    "truck_7.5t_diesel", "truck_7.5t_bev_fuso", "truck_7.5t_bev_quantron",
    "truck_18t_diesel", "truck_18t_diesel", "truck_18t_bev", "truck_18t_bev",
    "waste_25t_diesel", "waste_25t_bev_large", "waste_25t_bev_small",
    "hgv_40t_diesel", "hgv_40t_bev"
  )
)

validate_emission_factor_years <- function(years) {
  parsed_years <- as.integer(years)
  if (length(parsed_years) == 0 || any(is.na(parsed_years))) {
    stop("emission_factor_years must contain 2021, 2030 and/or 2050.", call. = FALSE)
  }

  unsupported <- setdiff(parsed_years, c(2021L, 2030L, 2050L))
  if (length(unsupported) > 0) {
    stop("Unsupported emission_factor_years: ", paste(unsupported, collapse = ", "), call. = FALSE)
  }

  unique(parsed_years)
}

build_energy_emission_input <- function(tour_distances, selected_plan_cost_legs, paths) {
  source_file <- "analysis/commercialTraffic/tourAnalysis_distances.csv"
  if (is.null(selected_plan_cost_legs) || nrow(selected_plan_cost_legs) == 0) {
    return(list(data = tour_distances, source_file = source_file))
  }

  required_leg_columns <- c(
    "source_role", "paper_component", "person", "leg_vehicle_type", "route_distance_km"
  )
  if (!all(required_leg_columns %in% names(selected_plan_cost_legs))) {
    return(list(data = tour_distances, source_file = source_file))
  }

  all_agent_components <- c("FTL", "longDistanceFreight")
  if (should_build_ltl_analyses(paths)) {
    all_agent_components <- c(ltl_components(), all_agent_components)
  }

  if (!all(c("groupOfSubpopulation", "personId", "vehicleType", "distanceInKm") %in% names(tour_distances))) {
    return(list(data = tour_distances, source_file = source_file))
  }

  represented_persons <- tibble(
    emission_component = paper_component_from_emission_group(clean_subpopulation(tour_distances$groupOfSubpopulation)),
    person = as.character(tour_distances$personId)
  ) %>%
    filter(emission_component %in% all_agent_components, !is.na(person), nzchar(person)) %>%
    distinct()

  fallback <- selected_plan_cost_legs %>%
    filter(
      source_role == "cutout_route",
      paper_component %in% all_agent_components,
      !is.na(person),
      nzchar(person),
      !is.na(leg_vehicle_type),
      nzchar(leg_vehicle_type),
      !is.na(route_distance_km),
      route_distance_km > 0
    ) %>%
    mutate(
      emission_component = ifelse(
        paper_component %in% ltl_components(),
        "LTL",
        paper_component
      ),
      person = as.character(person),
      vehicleType = as.character(leg_vehicle_type),
      distanceInKm = as_number(route_distance_km)
    ) %>%
    anti_join(represented_persons, by = c("emission_component", "person")) %>%
    group_by(emission_component, person, vehicleType) %>%
    summarise(
      distanceInKm = safe_sum(distanceInKm),
      .groups = "drop"
    ) %>%
    transmute(
      personId = as.character(person),
      vehicleId = as.character(person),
      vehicleType = as.character(vehicleType),
      groupOfSubpopulation = as.character(emission_component),
      dist_group = "selected_plan_missing_agent_fallback",
      distanceInKm = as.character(distanceInKm),
      distanceInKmWithDepotCharging = NA_character_,
      shareOfTravelDistanceWithDepotCharging = NA_character_
    )

  if (nrow(fallback) == 0) {
    return(list(data = tour_distances, source_file = source_file))
  }

  message(
    "Adding selected-plan WTW fallback rows for missing all-agent scope: ",
    paste(unique(fallback$groupOfSubpopulation), collapse = ", ")
  )
  common_columns <- intersect(
    c(
      "personId", "vehicleId", "vehicleType", "groupOfSubpopulation", "dist_group",
      "distanceInKm", "distanceInKmWithDepotCharging",
      "shareOfTravelDistanceWithDepotCharging"
    ),
    names(tour_distances)
  )
  tour_input <- tour_distances %>%
    mutate(across(all_of(common_columns), as.character))
  list(
    data = bind_rows(tour_input, fallback),
    source_file = paste_source_columns(
      source_file,
      "selected-plan cutout_route legs for missing LTL/FTL/longDistance agents"
    )
  )
}

build_energy_emissions <- function(tour_distances, paths, selected_plan_cost_legs = NULL) {
  if (nrow(tour_distances) == 0) {
    return(tibble())
  }

  factor_years <- validate_emission_factor_years(emission_factor_years)
  energy_input <- build_energy_emission_input(tour_distances, selected_plan_cost_legs, paths)

  result <- energy_input$data %>%
    mutate(
      groupOfSubpopulation = clean_subpopulation(.data$groupOfSubpopulation),
      vehicleType = as.character(.data$vehicleType),
      vehicle_type_base = normalize_vehicle_type(vehicleType),
      emission_vehicle_type_base = resolve_emission_vehicle_type_base(vehicle_type_base, paths),
      distanceInKm = as_number(.data$distanceInKm)
    ) %>%
    group_by(groupOfSubpopulation, vehicleType, vehicle_type_base, emission_vehicle_type_base) %>%
    summarise(
      tours_sample = n(),
      vehicle_km_sample_day = safe_sum(distanceInKm),
      vehicle_km_sample_year = vehicle_km_sample_day * workdays_per_year,
      vehicle_km_100pct_day = vehicle_km_sample_day * paths$sample_factor_to_100pct,
      vehicle_km_100pct_year = vehicle_km_100pct_day * workdays_per_year,
      .groups = "drop"
    ) %>%
    left_join(vehicle_type_to_key, by = c("emission_vehicle_type_base" = "vehicle_type_base")) %>%
    left_join(vehicle_intensities, by = "vehicle_key") %>%
    expand_grid(emission_factor_year = factor_years) %>%
    mutate(
      intensity_kg_co2e_per_100km = case_when(
        emission_factor_year == 2021L ~ intensity_kg_co2e_per_100km_2021,
        emission_factor_year == 2030L ~ intensity_kg_co2e_per_100km_2030,
        emission_factor_year == 2050L ~ intensity_kg_co2e_per_100km_2050
      ),
      energy_unit = ifelse(powertrain == "BEV", "kWh", "liter"),
      energy_amount_sample_day = vehicle_km_sample_day * energy_use_per_100km / 100,
      energy_amount_sample_year = energy_amount_sample_day * workdays_per_year,
      energy_amount_100pct_day = vehicle_km_100pct_day * energy_use_per_100km / 100,
      energy_amount_100pct_year = energy_amount_100pct_day * workdays_per_year,
      emission_kg_co2e_sample_day = vehicle_km_sample_day * intensity_kg_co2e_per_100km / 100,
      emission_t_co2e_sample_day = emission_kg_co2e_sample_day / 1000,
      emission_kg_co2e_sample_year = vehicle_km_sample_year * intensity_kg_co2e_per_100km / 100,
      emission_t_co2e_sample_year = emission_kg_co2e_sample_year / 1000,
      emission_kg_co2e_100pct_day = vehicle_km_100pct_day * intensity_kg_co2e_per_100km / 100,
      emission_t_co2e_100pct_day = emission_kg_co2e_100pct_day / 1000,
      emission_kg_co2e_100pct_year = vehicle_km_100pct_year * intensity_kg_co2e_per_100km / 100,
      emission_t_co2e_100pct_year = emission_kg_co2e_100pct_year / 1000,
      emission_kg_co2e_day = emission_kg_co2e_sample_day,
      emission_t_co2e_day = emission_t_co2e_sample_day,
      emission_kg_co2e_year = emission_kg_co2e_sample_year,
      emission_t_co2e_year = emission_t_co2e_sample_year,
      paper_relevance = "paper_wtw_emissions_source",
      sample_scope = paste0(paths$sample_tag, "pct_run"),
      metric_scope = "represented_cutout"
    ) %>%
    with_meta(paths, energy_input$source_file)

  unknown <- result %>%
    filter(is.na(vehicle_key)) %>%
    distinct(vehicle_type_base) %>%
    pull(vehicle_type_base)

  if (length(unknown) > 0) {
    add_warning("No WTW mapping for vehicle type(s): ", paste(unknown, collapse = ", "))
  }

  ev_heavy40t_as_diesel <- is_bev_fleet(paths) &&
    any(result$vehicle_type_base == "heavy40t" & result$powertrain == "diesel", na.rm = TRUE)
  if (ev_heavy40t_as_diesel) {
    stop("EV heavy40t is still mapped to diesel in the WTW emission table.", call. = FALSE)
  }

  result
}

# =============================================================================
# 7. Carrier-/jsprit-Ausgaben und Range-Recharge-Logs
# =============================================================================

find_freight_analysis_dirs <- function(paths) {
  roots <- c(paths$small_scale_dir, paths$generated_input_dir)
  roots <- roots[dir.exists(roots)]

  dirs <- unlist(lapply(roots, function(root) {
    list.dirs(root, recursive = TRUE, full.names = TRUE)
  }), use.names = FALSE)

  dirs[basename(dirs) == "freight" & basename(dirname(dirs)) == "analysis"]
}

is_part_analysis_dir <- function(path) {
  grepl("/part-[0-9]+-of-[0-9]+/", normalize_user_path(path)) ||
    grepl("/carrierParts/", normalize_user_path(path))
}

infer_component_from_path <- function(path) {
  text <- normalize_user_path(path)

  case_when(
    grepl("PARCEL", text, ignore.case = TRUE) ~ "LTL_Parcel",
    grepl("WASTE", text, ignore.case = TRUE) ~ "LTL_Waste",
    grepl("REST", text, ignore.case = TRUE) ~ "LTL_Rest",
    grepl("FTL", text, ignore.case = TRUE) ~ "FTL",
    grepl("commercialPersonTraffic", text, ignore.case = TRUE) ~ "commercialPersonTraffic",
    grepl("goodsTraffic", text, ignore.case = TRUE) ~ "smallScaleGoodsTraffic",
    TRUE ~ "unknown"
  )
}

read_carrier_outputs <- function(paths) {
  freight_dirs <- find_freight_analysis_dirs(paths)

  if (length(freight_dirs) == 0) {
    add_warning("No carrier analysis/freight directories found below sample/generatedInputData.")
    return(list(
      kpis = tibble(),
      time_distance_vehicle_type = tibble()
    ))
  }

  aggregate_dirs <- freight_dirs[!vapply(freight_dirs, is_part_analysis_dir, logical(1))]

  kpis <- read_carrier_kpis(aggregate_dirs, paths)
  time_distance <- if (should_build_ltl_analyses(paths)) {
    read_carrier_time_distance(aggregate_dirs, paths)
  } else {
    tibble()
  }

  list(
    kpis = kpis,
    time_distance_vehicle_type = time_distance
  )
}

read_carrier_kpis <- function(freight_dirs, paths) {
  purrr::map_dfr(freight_dirs, function(dir) {
    path <- file.path(dir, "Carriers_KPIs.tsv")
    if (!file.exists(path)) {
      return(tibble())
    }

    data <- safe_read_delim_auto(path, col_names = c("parameter", "value"))
    if (nrow(data) == 0) {
      return(tibble())
    }

    data %>%
      mutate(
        component = infer_component_from_path(dir),
        freight_analysis_dir = normalizePath(dir, winslash = "/", mustWork = FALSE),
        value_numeric = as_number(value),
        value_seconds = ifelse(grepl("time", parameter, ignore.case = TRUE), parse_duration_hms_to_seconds(value), NA_real_)
      ) %>%
      with_meta(paths, path)
  })
}

read_carrier_time_distance <- function(freight_dirs, paths) {
  purrr::map_dfr(freight_dirs, function(dir) {
    path <- file.path(dir, "TimeDistance_perVehicleType.tsv")
    if (!file.exists(path)) {
      return(tibble())
    }

    data <- safe_read_delim_auto(path)
    if (nrow(data) == 0) {
      return(tibble())
    }

    data <- data %>%
      normalize_names()

    required <- c("vehicletypeid", "nuofvehicles", "sumoftraveldistances_km", "totalcosts_eur")
    missing <- setdiff(required, names(data))
    if (length(missing) > 0) {
      stop("TimeDistance_perVehicleType.tsv misses required columns: ", paste(missing, collapse = ", "), call. = FALSE)
    }

    data %>%
      mutate(
        component = infer_component_from_path(dir),
        freight_analysis_dir = normalizePath(dir, winslash = "/", mustWork = FALSE),
        vehicle_type_base = normalize_vehicle_type(.data$vehicletypeid),
        is_recharge_vehicle_type = is_recharge_vehicle_type(.data$vehicletypeid),
        recharge_level = recharge_level(.data$vehicletypeid)
      ) %>%
      with_meta(paths, path)
  })
}

read_range_recharge_logs <- function(paths, range_log_scope = NULL) {
  if (!should_build_bev_range_feasibility(paths)) {
    return(tibble())
  }

  roots <- c(paths$small_scale_dir, paths$slurm_log_dir)
  roots <- roots[dir.exists(roots)]
  log_files <- unlist(lapply(roots, function(root) {
    list.files(root, pattern = "\\.log$", recursive = TRUE, full.names = TRUE)
  }), use.names = FALSE)

  if (length(log_files) == 0) {
    return(tibble())
  }
  log_files <- filter_primary_range_recharge_log_files(log_files)

  scope_agent_counts <- tibble(
    component = character(),
    agents_in_study_area_start_scope = numeric()
  )
  if (!is.null(range_log_scope)) {
    scope_agent_counts <- attr(range_log_scope, "agent_counts")
    if (is.null(scope_agent_counts)) {
      scope_agent_counts <- tibble(
        component = character(),
        agents_in_study_area_start_scope = numeric()
      )
    }
  }

  file_summaries <- purrr::map_dfr(log_files, function(path) {
    lines <- tryCatch(readLines(path, warn = FALSE), error = function(e) character())
    if (length(lines) == 0) {
      return(tibble())
    }

    required_multiplier <- extract_numeric_from_lines(lines, "requiredMultiplierApplications=([0-9]+)")
    current_range_util <- extract_numeric_from_lines(lines, "currentRangeUtilization=([0-9.]+)%")

    tibble(
      component = infer_component_from_path(path),
      log_file = normalizePath(path, winslash = "/", mustWork = FALSE),
      lines = length(lines),
      added_recharge_vehicle_type_lines = sum(grepl("Adding vehicle type .*Recharge", lines)),
      range_aware_summary_lines = sum(grepl("Range-aware loop check checked", lines)),
      recharge_range_evaluation_services = sum(extract_numeric_from_lines(lines, "([0-9]+) services needed Recharge-range evaluation")),
      added_high_cost_recharge_vehicles = sum(extract_numeric_from_lines(lines, "Added ([0-9]+) high-cost Recharge vehicles")),
      services_without_feasible_recharge_fallback =
        sum(extract_numeric_from_lines(lines, "([0-9]+) services had no feasible Recharge fallback")),
      restored_recharge_cost_capacity_lines = sum(grepl("Restored costs and capacities", lines)),
      max_required_multiplier_applications = ifelse(length(required_multiplier) == 0, NA_real_, max(required_multiplier, na.rm = TRUE)),
      max_current_range_utilization_pct = ifelse(length(current_range_util) == 0, NA_real_, max(current_range_util, na.rm = TRUE))
    ) %>%
      with_meta(paths, path)
  })

  service_events <- purrr::map_dfr(log_files, function(path) {
    lines <- tryCatch(readLines(path, warn = FALSE), error = function(e) character())
    if (length(lines) == 0) {
      return(tibble())
    }
    extract_range_recharge_service_events(lines, path, paths)
  })

  if (!is.null(range_log_scope)) {
    if (nrow(range_log_scope) == 0) {
      add_warning(
        "No study-area-start carrier scope could be built for range/recharge logs in ",
        paths$sample_dir,
        "; no KWM service events are included."
      )
      service_events <- service_events[0, , drop = FALSE]
    } else if (nrow(service_events) > 0) {
      service_events <- filter_range_recharge_events_to_scope(service_events, range_log_scope)
    }
  }

  summary_by_component <- file_summaries %>%
    group_by(component) %>%
    summarise(
      log_file = collapse_source_values(log_file),
      lines = safe_sum(lines),
      added_recharge_vehicle_type_lines = safe_sum(added_recharge_vehicle_type_lines),
      range_aware_summary_lines = safe_sum(range_aware_summary_lines),
      raw_recharge_range_evaluation_services = safe_sum(recharge_range_evaluation_services),
      raw_added_high_cost_recharge_vehicles = safe_sum(added_high_cost_recharge_vehicles),
      raw_services_without_feasible_recharge_fallback =
        safe_sum(services_without_feasible_recharge_fallback),
      restored_recharge_cost_capacity_lines = safe_sum(restored_recharge_cost_capacity_lines),
      max_required_multiplier_applications = safe_max(max_required_multiplier_applications),
      max_current_range_utilization_pct = safe_max(max_current_range_utilization_pct),
      .groups = "drop"
    ) %>%
    left_join(scope_agent_counts, by = "component")

  if (nrow(service_events) == 0) {
    return(summary_by_component %>%
      mutate(
        log_file = coalesce(range_log_source_label(component), log_file),
        selected_recharge_fallback_services = 0,
        range_infeasible_selected_recharge_services = 0,
        near_limit_selected_recharge_services = 0,
        any_recharge_evaluation_services = 0,
        agents_in_study_area_start_scope = coalesce(agents_in_study_area_start_scope, 0),
        services_without_feasible_recharge_fallback = 0,
        services_without_feasible_recharge_fallback_also_later_selected = 0,
        added_high_cost_recharge_vehicles = 0,
        services_with_added_recharge_vehicle = 0
      ))
  }

  event_counts <- service_events %>%
    filter(!is.na(service_id), nzchar(service_id)) %>%
    group_by(component) %>%
    summarise(
      selected_recharge_fallback_services =
        n_distinct(service_id[event_type == "selected_recharge_fallback"]),
      range_infeasible_selected_recharge_services =
        n_distinct(service_id[
          event_type == "selected_recharge_fallback" &
            !is.na(current_range_slack_km) &
            current_range_slack_km < 0
        ]),
      near_limit_selected_recharge_services = {
        selected_ids <- unique(service_id[event_type == "selected_recharge_fallback"])
        range_infeasible_ids <- unique(service_id[
          event_type == "selected_recharge_fallback" &
            !is.na(current_range_slack_km) &
            current_range_slack_km < 0
        ])
        sum(!selected_ids %in% range_infeasible_ids)
      },
      any_recharge_evaluation_services =
        n_distinct(service_id[event_type %in% c(
          "selected_recharge_fallback",
          "no_feasible_recharge_fallback",
          "near_limit_recharge_evaluation"
        )]),
      services_without_feasible_recharge_fallback =
        n_distinct(service_id[event_type == "no_feasible_recharge_fallback"]),
      services_without_feasible_recharge_fallback_also_later_selected = {
        selected_ids <- unique(service_id[event_type == "selected_recharge_fallback"])
        no_fallback_ids <- unique(service_id[event_type == "no_feasible_recharge_fallback"])
        sum(no_fallback_ids %in% selected_ids)
      },
      added_high_cost_recharge_vehicles =
        n_distinct(recharge_vehicle_id[
          event_type == "added_range_fallback_vehicle" &
            !is.na(recharge_vehicle_id) &
            nzchar(recharge_vehicle_id)
        ]),
      services_with_added_recharge_vehicle =
        n_distinct(service_id[event_type == "added_range_fallback_vehicle"]),
      event_log_file = collapse_source_values(log_file),
      .groups = "drop"
    )

  summary_by_component %>%
    full_join(event_counts, by = "component") %>%
    mutate(
      log_file = coalesce(range_log_source_label(component), log_file, event_log_file),
      selected_recharge_fallback_services = coalesce(selected_recharge_fallback_services, 0),
      range_infeasible_selected_recharge_services = coalesce(range_infeasible_selected_recharge_services, 0),
      near_limit_selected_recharge_services = coalesce(near_limit_selected_recharge_services, 0),
      any_recharge_evaluation_services = coalesce(any_recharge_evaluation_services, 0),
      agents_in_study_area_start_scope = coalesce(agents_in_study_area_start_scope, 0),
      services_without_feasible_recharge_fallback = coalesce(services_without_feasible_recharge_fallback, 0),
      services_without_feasible_recharge_fallback_also_later_selected =
        coalesce(services_without_feasible_recharge_fallback_also_later_selected, 0),
      added_high_cost_recharge_vehicles = coalesce(added_high_cost_recharge_vehicles, 0),
      services_with_added_recharge_vehicle = coalesce(services_with_added_recharge_vehicle, 0)
    ) %>%
    select(-event_log_file)
}

# Keeps only KWM service events belonging to carriers represented by study-area-start agents.
filter_range_recharge_events_to_scope <- function(service_events, range_log_scope) {
  service_events <- service_events %>%
    mutate(log_carrier_id = range_log_carrier_id(service_id))

  unmapped <- service_events %>%
    filter(is.na(log_carrier_id), !is.na(service_id), nzchar(service_id)) %>%
    count(component, name = "unmapped_events")
  if (nrow(unmapped) > 0) {
    add_warning(
      "Could not map ", safe_sum(unmapped$unmapped_events),
      " KWM range/recharge service events to a carrier ID; they are excluded from the study-area-start scope."
    )
  }

  service_events %>%
    inner_join(
      range_log_scope %>% mutate(in_study_area_start_scope = TRUE),
      by = c("component", "log_carrier_id" = "carrier_id")
    ) %>%
    select(-log_carrier_id, -in_study_area_start_scope)
}

# Extracts the carrier prefix shared by KWM service IDs and output_persons.csv.zst.
range_log_carrier_id <- function(service_id) {
  str_match(
    as.character(service_id),
    "^(Carrier_(?:Business|Goods)_[^_]+_purpose_[0-9]+(?:_vehTyp[^_]+)?)_"
  )[, 2]
}

filter_primary_range_recharge_log_files <- function(log_files) {
  primary <- log_files[grepl("\\.logfile\\.log$", basename(log_files))]
  if (length(primary) > 0) {
    return(primary)
  }

  log_files
}

range_log_source_label <- function(component) {
  case_when(
    component == "commercialPersonTraffic" ~
      "smallScaleCommercial/commercialPersonTraffic/**/*.logfile.log",
    component == "smallScaleGoodsTraffic" ~
      "smallScaleCommercial/goodsTraffic/**/*.logfile.log",
    TRUE ~ NA_character_
  )
}

extract_range_recharge_service_events <- function(lines, path, paths) {
  component <- infer_component_from_path(path)
  log_file <- normalizePath(path, winslash = "/", mustWork = FALSE)

  selected <- extract_range_recharge_service_event_rows(
    lines,
    "selected Recharge fallback for service '([^']+)'",
    "selected_recharge_fallback",
    component,
    log_file
  )
  no_fallback <- extract_range_recharge_service_event_rows(
    lines,
    "has service '([^']+)' that is outside the current free range/time-feasible fleet and still has no feasible Recharge fallback",
    "no_feasible_recharge_fallback",
    component,
    log_file
  )
  near_limit <- extract_range_recharge_service_event_rows(
    lines,
    "evaluates a Recharge fallback for service '([^']+)'",
    "near_limit_recharge_evaluation",
    component,
    log_file
  )

  added_matches <- str_match(
    lines,
    "Added range fallback vehicle '([^']+)' of type '([^']+)' to carrier '([^']+)' for service '([^']+)'"
  )
  added_indices <- which(!is.na(added_matches[, 1]))
  if (length(added_indices) == 0) {
    added <- tibble(
      component = character(),
      event_type = character(),
      service_id = character(),
      recharge_vehicle_id = character(),
      recharge_vehicle_type = character(),
      current_range_slack_km = numeric(),
      current_range_utilization_pct = numeric(),
      required_multiplier_applications = numeric(),
      log_file = character()
    )
  } else {
  added <- tibble(
    component = component,
    event_type = "added_range_fallback_vehicle",
    service_id = added_matches[added_indices, 5],
    recharge_vehicle_id = added_matches[added_indices, 2],
    recharge_vehicle_type = added_matches[added_indices, 3],
    current_range_slack_km = NA_real_,
    current_range_utilization_pct = NA_real_,
    required_multiplier_applications = NA_real_,
    log_file = log_file
  )
  }

  bind_rows(selected, no_fallback, near_limit, added) %>%
    with_meta(paths, basename(path))
}

extract_range_recharge_service_event_rows <- function(lines, service_pattern, event_type, component, log_file) {
  matches <- str_match(lines, service_pattern)
  indices <- which(!is.na(matches[, 1]))
  if (length(indices) == 0) {
    return(tibble(
      component = character(),
      event_type = character(),
      service_id = character(),
      recharge_vehicle_id = character(),
      recharge_vehicle_type = character(),
      current_range_slack_km = numeric(),
      current_range_utilization_pct = numeric(),
      required_multiplier_applications = numeric(),
      log_file = character()
    ))
  }

  event_lines <- lines[indices]
  tibble(
    component = component,
    event_type = event_type,
    service_id = matches[indices, 2],
    recharge_vehicle_id = NA_character_,
    recharge_vehicle_type = NA_character_,
    current_range_slack_km = as_number(str_match(event_lines, "currentRangeSlack=([-0-9.]+) km")[, 2]),
    current_range_utilization_pct = as_number(str_match(event_lines, "currentRangeUtilization=([0-9.]+)%")[, 2]),
    required_multiplier_applications = as_number(str_match(event_lines, "requiredMultiplierApplications=([0-9]+)")[, 2]),
    log_file = log_file
  )
}

extract_numeric_from_lines <- function(lines, pattern) {
  matches <- str_match(lines, pattern)[, 2]
  as_number(matches[!is.na(matches)])
}

# =============================================================================
# 8. Ausfuehrung
# =============================================================================

main()
