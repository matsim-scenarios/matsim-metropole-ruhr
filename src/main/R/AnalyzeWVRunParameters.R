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

# Hier den Run-Ordner oder den uebergeordneten Studienordner eintragen.
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

scenario_prefix <- "studyWV_Ruhr"
workdays_per_year <- 260

# Fuer die Emissionsfaktoren gilt wie im Paper: 2024 verwendet den
# Strommix-Faktor 2021.
emission_result_year <- year

# Fuer Kosten-Sensitivitaeten koennen dieselben ausgewaehlten Plaene mit
# Kostensaetzen anderer Jahre neu bewertet werden. Bei 2024 werden die
# Paper-Preisjahre 2024, 2030 und 2050 geschrieben.
cost_result_years <- if (year == 2024) c(2024, 2030, 2050) else year

# Optional: Falls die Preisjahr-Dateien nicht aus dem Run-/Studienordner
# ableitbar sind, koennen sie hier pro Jahr explizit eingetragen werden.
cost_vehicle_type_file_overrides <- c(
  # "2030" = "C:/path/to/mode-vehicles_withKwmCarrierVehicleTypes.xml.gz",
  # "2050" = "C:/path/to/mode-vehicles_withKwmCarrierVehicleTypes.xml.gz"
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
  message("Reading range and recharge logs...")
  range_logs <- read_range_recharge_logs(paths)
  energy_emissions <- build_energy_emissions(analysis_files$tour_distances, paths)
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
    selected_plan_measures,
    paper_costs_by_component,
    paths
  )

  paper_key_facts_total <- build_paper_key_facts_total(paper_key_facts_by_component)
  paper_comparable_costs <- build_paper_comparable_costs(paper_key_facts_by_component, paths)

  paper_tables <- build_paper_table_outputs(
    tour_distances = analysis_files$tour_distances,
    tour_durations = analysis_files$tour_durations,
    jobs_per_tour = analysis_files$jobs_per_tour,
    person_scores = person_scores,
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

path_exists <- function(path) {
  !is.na(path) && nzchar(path) && dir.exists(path)
}

resolve_selected_run <- function(root_url, selected_year, selected_fleet, selected_model,
                                 selected_sample_pct, prefix) {
  root <- normalize_user_path(root_url)
  sample_tag <- format_sample_pct(selected_sample_pct)
  sample_dir_name <- paste0("commercial_", sample_tag, "pct")
  final_run_name <- paste0("commercialTraffic_Run", sample_tag, "pct")
  scenario_name <- paste(prefix, selected_fleet, selected_model, selected_year, sep = "_")

  candidates <- unique(c(
    root,
    file.path(root, final_run_name),
    file.path(root, sample_dir_name, final_run_name),
    file.path(root, scenario_name, sample_dir_name, final_run_name),
    file.path(root, "output", "studyWV_Ruhr", scenario_name, sample_dir_name, final_run_name)
  ))

  final_candidates <- candidates[basename(candidates) == final_run_name]
  existing <- final_candidates[vapply(final_candidates, path_exists, logical(1))]

  if (length(existing) == 0 && basename(root) == final_run_name && dir.exists(root)) {
    existing <- root
  }

  if (length(existing) == 0) {
    stop(
      "Could not resolve final run folder. Checked:\n  ",
      paste(candidates, collapse = "\n  "),
      "\nPlease adjust run_folder_url or the year/fleet/model/sample variables at the top.",
      call. = FALSE
    )
  }

  final_run_dir <- normalizePath(existing[[1]], winslash = "/", mustWork = TRUE)
  sample_dir <- normalizePath(dirname(final_run_dir), winslash = "/", mustWork = TRUE)
  scenario_dir <- normalizePath(dirname(sample_dir), winslash = "/", mustWork = TRUE)
  study_dir <- normalizePath(dirname(scenario_dir), winslash = "/", mustWork = FALSE)

  inferred <- infer_scenario_meta(final_run_dir)

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
    year = ifelse(is.na(inferred$year), selected_year, inferred$year),
    fleet_type = ifelse(is.na(inferred$fleet_type), selected_fleet, inferred$fleet_type),
    model_type = ifelse(is.na(inferred$model_type), selected_model, inferred$model_type),
    sample_pct = as.numeric(selected_sample_pct),
    sample_tag = sample_tag,
    sample_factor_to_100pct = 100 / as.numeric(selected_sample_pct),
    final_run_name = basename(final_run_dir)
  )
}

infer_scenario_meta <- function(path) {
  text <- normalize_user_path(path)
  match <- str_match(text, "studyWV_Ruhr_(CV|EV|Mixed)_(Basic|Advanced)_([0-9]{4})")

  if (all(is.na(match))) {
    return(list(fleet_type = NA_character_, model_type = NA_character_, year = NA_integer_))
  }

  list(
    fleet_type = match[, 2],
    model_type = match[, 3],
    year = as.integer(match[, 4])
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

collapse_source_files <- function(data) {
  if (!"source_file" %in% names(data) || nrow(data) == 0) {
    return(NA_character_)
  }

  sources <- unique(na.omit(as.character(data$source_file)))
  if (length(sources) == 0) NA_character_ else paste(sources, collapse = ";")
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

# Opens plain, gzip or Zstandard-compressed text data for readr.
open_binary_connection <- function(path) {
  if (grepl("\\.gz$", path, ignore.case = TRUE)) {
    return(gzfile(path, open = "rb"))
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
    return(pipe(command, open = "rb"))
  }

  file(path, open = "rb")
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
  con <- open_binary_connection(path)
  on.exit(close(con), add = TRUE)

  readr::read_delim(
    con,
    delim = delim,
    col_names = col_names,
    col_types = readr::cols(.default = readr::col_character()),
    na = c("", "NA", "NaN", "null"),
    show_col_types = FALSE,
    progress = FALSE,
    locale = readr::locale(grouping_mark = ",")
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

    write_csv_compat(data, path)
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

write_csv_compat <- function(data, path, na = "") {
  dir.create(dirname(path), recursive = TRUE, showWarnings = FALSE)
  write_csv_args <- names(formals(readr::write_csv))

  if ("na" %in% write_csv_args) {
    return(readr::write_csv(data, path, na = na))
  }

  readr::write_csv(data, path)
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

safe_median <- function(x) {
  values <- x[!is.na(x)]
  if (length(values) == 0) {
    return(NA_real_)
  }
  stats::median(values)
}

safe_divide <- function(numerator, denominator) {
  ifelse(is.na(denominator) | denominator == 0, NA_real_, numerator / denominator)
}

clean_subpopulation <- function(x) {
  x <- as.character(x)
  x[is.na(x) | trimws(x) == ""] <- "unknown"
  x[x %in% c("commercialPersonTraffic_service", "Commercial Person Traffic Service")] <- "commercialPersonTraffic"
  x
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
  suffixes <- as.character(suffix)
  candidates <- unique(unlist(lapply(suffixes, function(one_suffix) {
    c(
      file.path(paths$final_run_dir, paste0(paths$final_run_name, one_suffix)),
      file.path(paths$final_run_dir, paste0(basename(paths$final_run_dir), one_suffix))
    )
  }), use.names = FALSE))

  existing <- candidates[file.exists(candidates)]
  if (length(existing) == 0) {
    return(NA_character_)
  }

  normalizePath(existing[[1]], winslash = "/", mustWork = TRUE)
}

# Tries several standard MATSim output suffixes and returns the first existing run file.
find_first_run_file <- function(paths, suffixes) {
  for (suffix in suffixes) {
    path <- find_run_file(paths, suffix)
    if (!is.na(path)) {
      return(path)
    }
  }

  NA_character_
}

# Creates a person lookup with the requested columns even when person scores are unavailable.
build_person_lookup <- function(person_scores, requested_cols) {
  if (nrow(person_scores) == 0) {
    return(tibble(person = character()) %>% add_missing_lookup_cols(requested_cols))
  }

  person_scores %>%
    select(any_of(requested_cols)) %>%
    add_missing_lookup_cols(requested_cols) %>%
    distinct()
}

# Adds absent lookup columns with NA values so downstream joins keep a stable shape.
add_missing_lookup_cols <- function(data, requested_cols) {
  missing_cols <- setdiff(requested_cols, names(data))
  for (col in missing_cols) {
    data[[col]] <- if (col == "goods_type") NA_real_ else NA_character_
  }

  data %>% select(all_of(requested_cols))
}

read_person_scores <- function(paths) {
  persons_file <- find_run_file(paths, c(".output_persons.csv.gz", ".output_persons.csv.zst", ".output_persons.csv"))
  if (is.na(persons_file)) {
    add_warning("Missing output_persons.csv.gz/.zst in final run: ", paths$final_run_dir)
    return(tibble())
  }

  persons <- safe_read_delim_auto(persons_file)
  if (nrow(persons) == 0) {
    return(tibble())
  }

  required <- c("person", "executed_score", "subpopulation")
  missing <- setdiff(required, names(persons))
  if (length(missing) > 0) {
    add_warning("Persons file misses required columns: ", paste(missing, collapse = ", "))
    return(tibble())
  }

  persons %>%
    transmute(
      person = as.character(.data$person),
      subpopulation = clean_subpopulation(.data$subpopulation),
      executed_score = as_number(.data$executed_score),
      cost = -1 * executed_score,
      carrier_id = if ("carrierId" %in% names(persons)) as.character(.data$carrierId) else NA_character_,
      tour_id = if ("tourId" %in% names(persons)) as.character(.data$tourId) else as.character(.data$person),
      goods_type = coalesce(
        if ("goods_type" %in% names(persons)) as_number(.data$goods_type) else NA_real_,
        if ("goodsType" %in% names(persons)) as_number(.data$goodsType) else NA_real_
      ),
      vehicle_types_raw = if ("vehicleTypes" %in% names(persons)) as.character(.data$vehicleTypes) else NA_character_
    ) %>%
    with_meta(paths, basename(persons_file))
}

read_trip_measures <- function(paths, person_scores) {
  trips_file <- find_run_file(paths, c(".output_trips.csv.gz", ".output_trips.csv.zst", ".output_trips.csv"))
  if (is.na(trips_file)) {
    add_warning("Missing output_trips.csv.gz/.zst in final run: ", paths$final_run_dir)
    return(tibble())
  }

  trips <- safe_read_delim_auto(trips_file)
  if (nrow(trips) == 0) {
    return(tibble())
  }

  required <- c("person", "trip_id", "trav_time", "traveled_distance", "main_mode")
  missing <- setdiff(required, names(trips))
  if (length(missing) > 0) {
    add_warning("Trips file misses expected columns: ", paste(missing, collapse = ", "))
  }

  person_lookup <- build_person_lookup(
    person_scores,
    c("person", "subpopulation", "goods_type", "carrier_id", "tour_id", "vehicle_types_raw")
  )

  trips %>%
    transmute(
      person = as.character(.data$person),
      trip_id = if ("trip_id" %in% names(trips)) as.character(.data$trip_id) else NA_character_,
      main_mode = if ("main_mode" %in% names(trips)) as.character(.data$main_mode) else NA_character_,
      traveled_distance_m = if ("traveled_distance" %in% names(trips)) as_number(.data$traveled_distance) else NA_real_,
      traveled_distance_km = traveled_distance_m / 1000,
      travel_time_s = if ("trav_time" %in% names(trips)) parse_time_to_seconds(.data$trav_time) else NA_real_,
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
  fallback_person_filter <- build_long_distance_person_filter(person_scores)

  for (source_index in seq_len(nrow(plan_sources))) {
    source_component_hint <- plan_sources$source_component_hint[[source_index]]
    plans_file <- plan_sources$plans_file[[source_index]]
    source_rank <- plan_sources$source_rank[[source_index]]
    source_role <- plan_sources$source_role[[source_index]]
    route_filter <- if (length(boundary_person_filter) > 0) boundary_person_filter else fallback_person_filter
    person_filter <- if (source_role %in% c("cutout_route", "combined_boundary_route")) route_filter else NULL

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
      paper_component = coalesce(boundary_component, paper_component_from_trip(subpopulation, goods_type), source_component_hint)
    )
}

# Builds the optional route-source person filter for longDistanceFreight and FTL.
build_long_distance_person_filter <- function(person_scores) {
  if (nrow(person_scores) == 0) {
    return(NULL)
  }

  selected <- person_scores %>%
    mutate(paper_component = paper_component_from_trip(subpopulation, goods_type)) %>%
    filter(paper_component %in% c("FTL", "longDistanceFreight")) %>%
    pull(person) %>%
    unique()

  if (length(selected) == 0) NULL else selected
}

# Finds component-specific long-distance plan files before falling back to final-run output plans.
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

  route_source <- find_first_run_file(paths, c(
    ".output_plans.xml.gz",
    ".output_plans.xml.zst",
    ".output_plans.xml",
    ".output_experienced_plans.xml.gz",
    ".output_experienced_plans.xml.zst",
    ".output_experienced_plans.xml"
  ))
  route_source_role <- if (nrow(component_sources) > 0) "cutout_route" else "combined_boundary_route"
  route_source_row <- if (is.na(route_source)) {
    tibble(source_component_hint = character(), plans_file = character(), source_rank = integer(), source_role = character())
  } else {
    tibble(source_component_hint = NA_character_, plans_file = route_source, source_rank = 2L, source_role = route_source_role)
  }

  if (nrow(component_sources) > 0) {
    return(bind_rows(component_sources, route_source_row))
  }

  route_source_row
}

# Reads selected-plan legs and score-relevant activities in one streaming pass for cost reconstruction.
read_selected_plan_cost_inputs <- function(paths, person_scores) {
  plans_file <- find_first_run_file(paths, c(
    ".output_plans.xml.gz",
    ".output_plans.xml.zst",
    ".output_plans.xml",
    ".output_experienced_plans.xml.gz",
    ".output_experienced_plans.xml.zst",
    ".output_experienced_plans.xml"
  ))
  if (is.na(plans_file)) {
    add_warning("Missing selected-plan XML for cost reconstruction in final run: ", paths$final_run_dir)
    return(list(legs = tibble(), activities = tibble()))
  }

  parsed <- tryCatch(
    parse_selected_plan_cost_inputs(plans_file),
    error = function(e) {
      add_warning("Could not parse selected-plan cost inputs from ", plans_file, ": ", conditionMessage(e))
      list(legs = empty_selected_plan_cost_legs(), activities = empty_selected_plan_cost_activities())
    }
  )

  list(
    legs = decorate_selected_plan_cost_legs(parsed$legs, plans_file, person_scores, paths),
    activities = decorate_selected_plan_cost_activities(parsed$activities, plans_file, person_scores, paths)
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
decorate_selected_plan_cost_legs <- function(parsed, plans_file, person_scores, paths) {
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
      paper_component = paper_component_from_cost_person(subpopulation, goods_type),
      leg_vehicle_type = mapply(
        resolve_vehicle_type_for_leg,
        leg_mode,
        vehicle_types_raw,
        vehicle_type_values,
        USE.NAMES = FALSE
      ),
      leg_vehicle_type_base = normalize_vehicle_type(leg_vehicle_type),
      selected_plan_source_file = normalizePath(plans_file, winslash = "/", mustWork = FALSE),
      route_distance_km = route_distance_m / 1000,
      route_travel_time_h = route_travel_time_s / 3600
    ) %>%
    select(
      person, leg_index, leg_mode, leg_vehicle_type, leg_vehicle_type_base,
      route_distance_m, route_travel_time_s, route_distance_km, route_travel_time_h,
      vehicle_types_raw, vehicle_type_values, subpopulation, goods_type, carrier_id, tour_id,
      paper_component, selected_plan_source_file
    ) %>%
    with_meta(paths, basename(plans_file))
}

# Adds person metadata and resolved vehicle types to selected-plan activity rows.
decorate_selected_plan_cost_activities <- function(parsed, plans_file, person_scores, paths) {
  if (nrow(parsed) == 0) {
    return(tibble())
  }

  person_lookup <- build_person_lookup(
    person_scores,
    c("person", "subpopulation", "goods_type", "carrier_id", "tour_id", "vehicle_types_raw")
  )

  parsed %>%
    left_join(person_lookup, by = "person") %>%
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
      paper_component = paper_component_from_cost_person(subpopulation, goods_type),
      activity_vehicle_type = mapply(
        resolve_vehicle_type_for_leg,
        activity_leg_mode_hint,
        vehicle_types_raw,
        vehicle_type_values,
        USE.NAMES = FALSE
      ),
      activity_vehicle_type_base = normalize_vehicle_type(activity_vehicle_type),
      selected_plan_source_file = normalizePath(plans_file, winslash = "/", mustWork = FALSE),
      activity_duration_h = activity_duration_s / 3600
    ) %>%
    filter(is_score_relevant_cost_activity(activity_type), activity_duration_s > 0) %>%
    select(
      person, activity_index, activity_type, activity_duration_s, activity_duration_h,
      activity_leg_mode_hint, activity_vehicle_type, activity_vehicle_type_base,
      vehicle_types_raw, vehicle_type_values, subpopulation, goods_type, carrier_id, tour_id,
      paper_component, selected_plan_source_file
    ) %>%
    with_meta(paths, basename(plans_file))
}

# Reads the selected final-run plan as leg-level input for vehicle-type-specific cost reconstruction.
read_selected_plan_cost_legs <- function(paths, person_scores) {
  plans_file <- find_first_run_file(paths, c(
    ".output_plans.xml.gz",
    ".output_plans.xml.zst",
    ".output_plans.xml",
    ".output_experienced_plans.xml.gz",
    ".output_experienced_plans.xml.zst",
    ".output_experienced_plans.xml"
  ))
  if (is.na(plans_file)) {
    add_warning("Missing selected-plan XML for cost reconstruction in final run: ", paths$final_run_dir)
    return(tibble())
  }

  parsed <- tryCatch(
    parse_selected_plan_cost_legs(plans_file),
    error = function(e) {
      add_warning("Could not parse selected-plan cost legs from ", plans_file, ": ", conditionMessage(e))
      empty_selected_plan_cost_legs()
    }
  )
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
      paper_component = paper_component_from_cost_person(subpopulation, goods_type),
      leg_vehicle_type = mapply(
        resolve_vehicle_type_for_leg,
        leg_mode,
        vehicle_types_raw,
        vehicle_type_values,
        USE.NAMES = FALSE
      ),
      leg_vehicle_type_base = normalize_vehicle_type(leg_vehicle_type),
      selected_plan_source_file = normalizePath(plans_file, winslash = "/", mustWork = FALSE),
      route_distance_km = route_distance_m / 1000,
      route_travel_time_h = route_travel_time_s / 3600
    ) %>%
    select(
      person, leg_index, leg_mode, leg_vehicle_type, leg_vehicle_type_base,
      route_distance_m, route_travel_time_s, route_distance_km, route_travel_time_h,
      vehicle_types_raw, vehicle_type_values, subpopulation, goods_type, carrier_id, tour_id,
      paper_component, selected_plan_source_file
    ) %>%
    with_meta(paths, basename(plans_file))
}

# Streams selected plans and emits one row per routed leg for cost reconstruction.
parse_selected_plan_cost_legs <- function(plans_file, person_filter = NULL) {
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
    vehicle_type_lines <- grepl('attribute name="vehicleTypes"', lines, fixed = TRUE)
    plan_lines <- grepl("<plan ", lines, fixed = TRUE)
    leg_lines <- grepl("<leg ", lines, fixed = TRUE)
    route_lines <- grepl("<route ", lines, fixed = TRUE)
    plan_end_lines <- grepl("</plan>", lines, fixed = TRUE)
    relevant_lines <- which(
      person_start_lines | person_end_lines | vehicle_type_lines |
        plan_lines | leg_lines | route_lines | plan_end_lines
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
        current_leg_travel_time_s <- NA_real_
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

        row_index <- row_index + 1L
        parsed_rows[[row_index]] <- tibble(
          person = current_person,
          leg_index = current_leg_index,
          leg_mode = current_leg_mode,
          route_distance_m = ifelse(is.na(route_distance_m), 0, route_distance_m),
          route_travel_time_s = ifelse(is.na(route_travel_time_s), 0, route_travel_time_s),
          plan_vehicle_types_raw = current_vehicle_types_raw,
          plan_vehicle_type_values = current_vehicle_type_values
        )

        current_leg_mode <- NA_character_
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

  if (length(parsed_rows) == 0) {
    return(empty_selected_plan_cost_legs())
  }

  bind_rows(parsed_rows)
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

# Reads selected-plan activities whose durations enter vehicle-type-specific cost scoring.
read_selected_plan_cost_activities <- function(paths, person_scores) {
  plans_file <- find_first_run_file(paths, c(
    ".output_plans.xml.gz",
    ".output_plans.xml.zst",
    ".output_plans.xml",
    ".output_experienced_plans.xml.gz",
    ".output_experienced_plans.xml.zst",
    ".output_experienced_plans.xml"
  ))
  if (is.na(plans_file)) {
    add_warning("Missing selected-plan XML for activity-cost reconstruction in final run: ", paths$final_run_dir)
    return(tibble())
  }

  parsed <- tryCatch(
    parse_selected_plan_cost_activities(plans_file),
    error = function(e) {
      add_warning("Could not parse selected-plan cost activities from ", plans_file, ": ", conditionMessage(e))
      empty_selected_plan_cost_activities()
    }
  )
  if (nrow(parsed) == 0) {
    return(tibble())
  }

  person_lookup <- build_person_lookup(
    person_scores,
    c("person", "subpopulation", "goods_type", "carrier_id", "tour_id", "vehicle_types_raw")
  )

  parsed %>%
    left_join(person_lookup, by = "person") %>%
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
      paper_component = paper_component_from_cost_person(subpopulation, goods_type),
      activity_vehicle_type = mapply(
        resolve_vehicle_type_for_leg,
        activity_leg_mode_hint,
        vehicle_types_raw,
        vehicle_type_values,
        USE.NAMES = FALSE
      ),
      activity_vehicle_type_base = normalize_vehicle_type(activity_vehicle_type),
      selected_plan_source_file = normalizePath(plans_file, winslash = "/", mustWork = FALSE),
      activity_duration_h = activity_duration_s / 3600
    ) %>%
    filter(is_score_relevant_cost_activity(activity_type), activity_duration_s > 0) %>%
    select(
      person, activity_index, activity_type, activity_duration_s, activity_duration_h,
      activity_leg_mode_hint, activity_vehicle_type, activity_vehicle_type_base,
      vehicle_types_raw, vehicle_type_values, subpopulation, goods_type, carrier_id, tour_id,
      paper_component, selected_plan_source_file
    ) %>%
    with_meta(paths, basename(plans_file))
}

# Streams selected plans and emits activity durations for service/handling cost reconstruction.
parse_selected_plan_cost_activities <- function(plans_file, person_filter = NULL) {
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
  current_activity_index <- 0L
  parsed_rows <- list()
  row_index <- 0L

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
    plan_end_lines <- grepl("</plan>", lines, fixed = TRUE)
    relevant_lines <- which(
      person_start_lines | person_end_lines | vehicle_type_lines |
        plan_lines | activity_lines | leg_lines | plan_end_lines
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
        current_activity_index <- 0L
      }

      if (inside_selected_plan && activity_lines[[line_index]]) {
        current_activity_index <- current_activity_index + 1L
        activity_duration_s <- parse_activity_duration_to_seconds(line)
        row_index <- row_index + 1L
        parsed_rows[[row_index]] <- tibble(
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
        current_leg_mode <- xml_attr(line, "mode")
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

  if (length(parsed_rows) == 0) {
    return(empty_selected_plan_cost_activities())
  }

  bind_rows(parsed_rows)
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
  is_ltl <- normalized %in% c("LTL", "LTL_trip", "LTL_trips")

  case_when(
    normalized %in% c("FTL", "FTL_trip", "FTL_kv_trip") ~ "FTL",
    normalized == "longDistanceFreight" ~ "longDistanceFreight",
    is_ltl & goods_type == 140 ~ "wasteCollection",
    is_ltl & goods_type == 150 ~ "CEP",
    is_ltl ~ "remainingLTL",
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
build_paper_key_facts_by_component <- function(tour_distances, tour_durations, trip_measures,
                                               selected_plan_measures, paper_costs_by_component, paths) {
  small_components <- build_small_component_key_facts(tour_distances, tour_durations, paths)
  trip_components <- build_trip_component_key_facts(trip_measures, paths)
  selected_long_distance <- build_selected_long_distance_key_facts(selected_plan_measures, trip_components, paths)

  if (nrow(selected_long_distance) > 0) {
    trip_components <- trip_components %>%
      filter(!component %in% c("FTL", "longDistanceFreight"))
  } else if (any(trip_components$component %in% c("FTL", "longDistanceFreight"))) {
    add_warning(
      "No selected-plan longDistanceFreight/FTL values available; paper_key_facts_by_component uses ",
      "represented cutout values from output_trips.csv for these components."
    )
  }

  rows <- bind_rows(small_components, trip_components, selected_long_distance)
  if (nrow(rows) == 0) {
    return(tibble())
  }
  rows <- apply_reconstructed_costs_to_key_facts(rows, paper_costs_by_component, paths)

  rows %>%
    mutate(
      component_group = component_group_from_paper_component(component),
      avg_distance_km_per_vehicle = safe_divide(distance_km, vehicles),
      avg_travel_time_h_per_vehicle = safe_divide(travel_time_h, vehicles)
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
      total_cost_eur = ifelse(has_reconstructed_cost, reconstructed_total_cost_eur, total_cost_eur),
      source_dataset = ifelse(
        has_reconstructed_cost,
        paste_source_columns(source_dataset, reconstructed_source_dataset),
        source_dataset
      ),
      source_file = paste_source_columns(source_file, reconstructed_source_file)
    ) %>%
    select(
      -reconstructed_total_cost_eur,
      -reconstructed_source_dataset, -reconstructed_source_file, -has_reconstructed_cost
    )
}

# Uses tourAnalysis_distances/durations for components restricted to study-area-start tours.
build_small_component_key_facts <- function(tour_distances, tour_durations, paths) {
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
      trips = NA_real_,
      distance_km = safe_sum(distance_km_value),
      .groups = "drop"
    )

  duration_summary <- tibble(component = character(), travel_time_h = numeric())
  if (nrow(tour_durations) > 0) {
    duration_summary <- tour_durations %>%
      mutate(
        component = paper_component_from_tour_group(.data$groupOfSubpopulation),
        duration_h_value = as_number(.data$tourDurationsInHours)
      ) %>%
      filter(!is.na(component)) %>%
      group_by(component) %>%
      summarise(travel_time_h = safe_sum(duration_h_value), .groups = "drop")
  }

  distance_summary %>%
    left_join(duration_summary, by = "component") %>%
    mutate(
      total_cost_eur = NA_real_,
      paper_relevance = "paper_canonical_key_facts",
      sample_scope = paste0(paths$sample_tag, "pct_run"),
      evaluation_scope = "study_area_start",
      metric_scope = "study_area_start",
      source_dataset = "tourAnalysis_distances.csv;tourAnalysis_durations.csv",
      inclusion_rule = "commercialPersonTraffic and goodsTraffic agents contained in analysis/commercialTraffic/tourAnalysis_distances.csv",
      source_file = "analysis/commercialTraffic/tourAnalysis_distances.csv;analysis/commercialTraffic/tourAnalysis_durations.csv"
    ) %>%
    with_meta(paths, "analysis/commercialTraffic/tourAnalysis_distances.csv;analysis/commercialTraffic/tourAnalysis_durations.csv")
}

# Uses output_trips joined with output_persons for all-agent LTL and fallback long-distance facts.
build_trip_component_key_facts <- function(trip_measures, paths) {
  if (nrow(trip_measures) == 0) {
    return(tibble())
  }

  trip_measures %>%
    mutate(component = paper_component_from_trip(subpopulation, goods_type)) %>%
    filter(component %in% c("wasteCollection", "CEP", "remainingLTL", "FTL", "longDistanceFreight")) %>%
    group_by(component) %>%
    summarise(
      vehicles = n_distinct(person),
      tours = NA_real_,
      trips = n(),
      distance_km = safe_sum(traveled_distance_km),
      travel_time_h = safe_sum(travel_time_h),
      total_cost_eur = NA_real_,
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    mutate(
      paper_relevance = ifelse(
        component %in% c("FTL", "longDistanceFreight"),
        "fallback_only_until_selected_plan_values_are_available",
        "paper_canonical_key_facts"
      ),
      sample_scope = paste0(paths$sample_tag, "pct_run"),
      evaluation_scope = "all_generated_agents",
      metric_scope = "represented_cutout",
      source_dataset = "output_trips.csv joined with output_persons.csv",
      inclusion_rule = "FTL, longDistanceFreight, wasteCollection, CEP and remainingLTL include all agents found in output_trips.csv"
    ) %>%
    copy_analysis_meta(trip_measures)
}

# Uses selected-plan boundary attributes for longDistanceFreight/FTL where full operational sections matter.
build_selected_long_distance_key_facts <- function(selected_plan_measures, trip_components, paths) {
  if (nrow(selected_plan_measures) == 0) {
    return(tibble())
  }

  boundary_summary <- selected_plan_measures %>%
    filter(paper_component %in% c("FTL", "longDistanceFreight"), source_role %in% c("boundary", "combined_boundary_route")) %>%
    group_by(component = paper_component) %>%
    summarise(
      boundary_vehicles = n_distinct(person),
      boundary_distance_km = safe_sum(selected_plan_boundary_distance_km),
      boundary_travel_time_h = safe_sum(selected_plan_boundary_travel_time_h),
      boundary_source_file = paste(unique(source_file), collapse = ";"),
      .groups = "drop"
    )

  route_summary <- selected_plan_measures %>%
    filter(paper_component %in% c("FTL", "longDistanceFreight"), source_role %in% c("cutout_route", "combined_boundary_route")) %>%
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

  fallback_selected_summary <- selected_plan_measures %>%
    filter(paper_component %in% c("FTL", "longDistanceFreight")) %>%
    group_by(component = paper_component) %>%
    summarise(
      fallback_route_distance_km = safe_sum(selected_plan_route_distance_km),
      fallback_route_travel_time_h = safe_sum(selected_plan_route_travel_time_h),
      .groups = "drop"
    )

  trip_cutout <- trip_components %>%
    filter(component %in% c("FTL", "longDistanceFreight")) %>%
    transmute(
      component,
      cutout_trips = trips,
      cutout_distance_km = distance_km,
      cutout_travel_time_h = travel_time_h
    )

  selected_summary %>%
    left_join(fallback_selected_summary, by = "component") %>%
    left_join(trip_cutout, by = "component") %>%
    mutate(
      has_boundary_attributes = !is.na(boundary_distance_km) & boundary_distance_km > 0,
      has_cutout_distance = !is.na(cutout_distance_km),
      has_route_distance = !is.na(route_distance_km) & route_distance_km > 0,
      has_route_travel_time = !is.na(route_travel_time_h) & route_travel_time_h > 0,
      distance_km = case_when(
        has_boundary_attributes & has_route_distance ~ route_distance_km + boundary_distance_km,
        has_boundary_attributes & has_cutout_distance ~ cutout_distance_km + boundary_distance_km,
        route_distance_km > 0 ~ route_distance_km,
        fallback_route_distance_km > 0 ~ fallback_route_distance_km,
        TRUE ~ cutout_distance_km
      ),
      travel_time_h = case_when(
        has_boundary_attributes & has_route_travel_time ~ route_travel_time_h + boundary_travel_time_h,
        route_travel_time_h > 0 ~ route_travel_time_h,
        fallback_route_travel_time_h > 0 ~ fallback_route_travel_time_h,
        TRUE ~ cutout_travel_time_h
      ),
      trips = cutout_trips,
      tours = NA_real_,
      total_cost_eur = NA_real_,
      paper_relevance = "paper_canonical_key_facts",
      sample_scope = paste0(paths$sample_tag, "pct_run"),
      evaluation_scope = "all_generated_agents",
      metric_scope = ifelse(has_boundary_attributes & has_route_travel_time, "boundary_adjusted_operational", "selected_plan_or_trip_fallback"),
      source_dataset = ifelse(
        has_boundary_attributes & has_route_travel_time,
        "output_trips.csv + final selected plans + component source plans XML boundary attributes",
        "selected plans XML or output_trips.csv fallback"
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
        paper_component %in% c("wasteCollection", "CEP", "remainingLTL", "FTL", "longDistanceFreight") ~ TRUE,
        TRUE ~ FALSE
      )
    ) %>%
    filter(include_in_paper_cost_filter)

  if (nrow(eligible_legs) == 0) {
    add_warning("No selected-plan legs remain after applying paper cost scopes.")
    return(tibble())
  }
  eligible_activities <- apply_paper_cost_filter_to_activities(selected_plan_cost_activities, study_area_start_scope)

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
  if (nrow(tour_distances) == 0 || !"personId" %in% names(tour_distances)) {
    return(tibble(component = character(), person = character()))
  }

  tour_distances %>%
    mutate(
      component = paper_component_from_tour_group(.data$groupOfSubpopulation),
      person = as.character(.data$personId)
    ) %>%
    filter(!is.na(component), !is.na(person), nzchar(person)) %>%
    distinct(component, person)
}

# Applies the same paper component scope to activity rows as to route-leg cost rows.
apply_paper_cost_filter_to_activities <- function(selected_plan_cost_activities, study_area_start_scope) {
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
        paper_component %in% c("wasteCollection", "CEP", "remainingLTL", "FTL", "longDistanceFreight") ~ TRUE,
        TRUE ~ FALSE
      )
    ) %>%
    filter(include_in_paper_cost_filter)
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
      leg_vehicle_type_base = activity_vehicle_type_base,
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

# Adds exact or base-vehicle fallback cost rates to expanded selected-plan legs.
attach_vehicle_cost_rates <- function(expanded_legs, vehicle_cost_parameters) {
  rates <- vehicle_cost_parameters %>%
    transmute(
      cost_result_year = as.integer(cost_result_year),
      cost_vehicle_type = vehicle_type,
      cost_vehicle_type_base = vehicle_type_base,
      fixed_costs_per_day,
      costs_per_meter,
      costs_per_second,
      cost_vehicle_source_file = source_file
    ) %>%
    distinct()

  exact <- expanded_legs %>%
    left_join(rates, by = c("cost_result_year", "leg_vehicle_type" = "cost_vehicle_type")) %>%
    mutate(cost_vehicle_type = leg_vehicle_type)

  missing_exact <- exact %>%
    mutate(missing_cost_rate = is.na(fixed_costs_per_day) & is.na(costs_per_meter) & is.na(costs_per_second))

  base_rates <- rates %>%
    arrange(cost_result_year, cost_vehicle_type) %>%
    distinct(cost_result_year, cost_vehicle_type_base, .keep_all = TRUE)

  fallback <- missing_exact %>%
    filter(missing_cost_rate) %>%
    select(all_of(names(expanded_legs))) %>%
    left_join(base_rates, by = c("cost_result_year", "leg_vehicle_type_base" = "cost_vehicle_type_base")) %>%
    mutate(cost_vehicle_type = coalesce(cost_vehicle_type, leg_vehicle_type))

  bind_rows(
    missing_exact %>% filter(!missing_cost_rate),
    fallback
  ) %>%
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
    filter(paper_component %in% c("FTL", "longDistanceFreight"), source_role %in% c("boundary", "combined_boundary_route")) %>%
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

  bind_rows(
    summarise_paper_cost_total(component_rows, "LTL", c("wasteCollection", "CEP", "remainingLTL"), FALSE),
    summarise_paper_cost_total(component_rows, "small-scale goods total", c("smallScaleGoodsTraffic", "wasteCollection", "CEP", "remainingLTL"), FALSE),
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

  bind_rows(
    summarise_paper_total(paper_key_facts_by_component, "LTL", c("wasteCollection", "CEP", "remainingLTL")),
    summarise_paper_total(paper_key_facts_by_component, "small-scale goods total", c("smallScaleGoodsTraffic", "wasteCollection", "CEP", "remainingLTL")),
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
      distance_km = safe_sum(distance_km),
      travel_time_h = safe_sum(travel_time_h),
      total_cost_eur = safe_sum(total_cost_eur),
      avg_distance_km_per_vehicle = safe_divide(distance_km, vehicles),
      avg_travel_time_h_per_vehicle = safe_divide(travel_time_h, vehicles),
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
    normalized %in% c("FTL", "FTL_trip", "FTL_kv_trip") ~ "FTL",
    normalized == "longDistanceFreight" ~ "longDistanceFreight",
    normalized %in% c("LTL", "LTL_trip", "LTL_trips") ~ "LTL",
    TRUE ~ normalized
  )
}

# Builds paper-table-shaped CSV rows for one selected run.
build_paper_table_outputs <- function(tour_distances, tour_durations, jobs_per_tour, person_scores,
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

  list(
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
    paper_table_cost_repricing = build_paper_table_cost_repricing(paper_costs_by_component, paths),
    paper_table_bev_range_feasibility = build_paper_table_bev_range_feasibility(jobs_per_tour, range_logs, paths),
    paper_table_ltl_recharge_vehicle_use = build_paper_table_ltl_recharge_vehicle_use(jobs_per_tour, person_scores, paths),
    paper_table_mixed_ltl_fleet_composition = build_paper_table_mixed_ltl_fleet_composition(
      carrier_time_distance_vehicle_type,
      paths
    ),
    paper_table_mixed_ltl_wtw_emissions = build_paper_table_mixed_ltl_wtw_emissions(
      carrier_time_distance_vehicle_type,
      paths
    )
  )
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
    filter(!is.na(component)) %>%
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
      filter(!is.na(component)) %>%
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
      filter(!is.na(component)) %>%
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

  optional_cols <- c("route_distance_km", "cutout_distance_km", "route_travel_time_h", "cutout_travel_time_h")
  for (col in setdiff(optional_cols, names(paper_key_facts_by_component))) {
    paper_key_facts_by_component[[col]] <- NA_real_
  }

  paper_key_facts_by_component %>%
    filter(!component %in% existing_components, component_group != "total") %>%
    mutate(
      sample_size_distance_km = coalesce(route_distance_km, cutout_distance_km, distance_km),
      sample_size_duration_h = coalesce(route_travel_time_h, cutout_travel_time_h, travel_time_h)
    ) %>%
    transmute(
      component,
      agents_tours = vehicles,
      total_distance_km = sample_size_distance_km,
      total_duration_h = sample_size_duration_h,
      stops_in_sample = case_when(
        component %in% c("longDistanceFreight", "FTL") ~ vehicles,
        !is.na(tours) ~ tours,
        !is.na(trips) ~ trips,
        TRUE ~ vehicles
      ),
      source_file
    )
}

# Extracts Jsprit CPU time by paper component from carrier-level KPI files.
build_component_cpu_time <- function(carrier_kpis) {
  if (nrow(carrier_kpis) == 0) {
    return(tibble(component = character(), cpu_time_h = numeric(), cpu_source_file = character()))
  }

  if (!"value_seconds" %in% names(carrier_kpis)) {
    carrier_kpis$value_seconds <- NA_real_
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
      total_distance_traveled_km = round(distance_km, 0),
      total_time_traveled_h = round(travel_time_h, 0),
      total_cost_eur = round(total_cost_eur, 0),
      included_in_comparable_cost,
      comparable_cost_eur = round(comparable_cost_eur, 0),
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
      reported_distance_km = round(distance_km, 0),
      reported_time_h = round(travel_time_h, 0),
      avg_distance_km_per_vehicle = round(avg_distance_km_per_vehicle, 2),
      avg_time_h_per_vehicle = round(avg_travel_time_h_per_vehicle, 2),
      sample_scope, evaluation_scope, metric_scope, source_file
    )
}

# Formats single-run WTW emissions by component and total in one appendix-style table.
build_paper_table_wtw_emissions <- function(energy_emissions, paths) {
  if (nrow(energy_emissions) == 0) {
    return(tibble())
  }

  by_component <- energy_emissions %>%
    mutate(component = paper_component_from_emission_group(groupOfSubpopulation)) %>%
    group_by(component) %>%
    summarise(
      scenario_name = first(scenario_name),
      demand_generation = first(model_type),
      vehicle_type = paper_vehicle_type_label(first(fleet_type)),
      year = first(requested_emission_year),
      row_type = "component",
      vehicle_km_million_day = safe_sum(vehicle_km_sample_day) / 1e6,
      gasoline_million_l_year = safe_sum(ifelse(powertrain == "gasoline", energy_amount_sample_year, NA_real_)) / 1e6,
      diesel_million_l_year = safe_sum(ifelse(powertrain == "diesel", energy_amount_sample_year, NA_real_)) / 1e6,
      electricity_gwh_year = safe_sum(ifelse(powertrain == "BEV", energy_amount_sample_year, NA_real_)) / 1e6,
      wtw_emissions_kt_year = safe_sum(emission_t_co2e_sample_year) / 1000,
      source_file = paste(unique(source_file), collapse = ";"),
      .groups = "drop"
    )

  total <- energy_emissions %>%
    summarise(
      scenario_name = first(scenario_name),
      demand_generation = first(model_type),
      vehicle_type = paper_vehicle_type_label(first(fleet_type)),
      year = first(requested_emission_year),
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
      scenario_name, demand_generation, vehicle_type, year,
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
      total_distance_traveled_km = round(distance_km, 0),
      total_time_traveled_h = round(travel_time_h, 0),
      activity_time_h = round(activity_time_h, 0),
      fixed_cost_eur = round(fixed_cost_eur, 0),
      distance_cost_eur = round(distance_cost_eur + boundary_distance_cost_eur, 0),
      travel_time_cost_eur = round(time_cost_eur + boundary_time_cost_eur, 0),
      activity_time_cost_eur = round(activity_time_cost_eur, 0),
      time_cost_eur = round(time_cost_eur + boundary_time_cost_eur + activity_time_cost_eur, 0),
      total_cost_eur = round(total_cost_eur, 0),
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
  if (paths$fleet_type != "EV" || nrow(jobs_per_tour) == 0) {
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
    jobs_not_feasible_without_recharging = numeric(),
    recharge_vehicles_added = numeric(),
    services_without_recharge_fallback = numeric(),
    range_source_file = character()
  )
  if (!is.null(range_logs) && nrow(range_logs) > 0 && "component" %in% names(range_logs)) {
    range_rows <- range_logs %>%
      filter(component %in% c("commercialPersonTraffic", "smallScaleGoodsTraffic")) %>%
      group_by(component) %>%
      summarise(
        jobs_not_feasible_without_recharging = safe_sum(recharge_range_evaluation_services),
        recharge_vehicles_added = safe_sum(added_high_cost_recharge_vehicles),
        services_without_recharge_fallback = safe_sum(no_feasible_recharge_fallback_services),
        range_source_file = collapse_source_values(log_file),
        .groups = "drop"
      )
  }

  component_rows <- job_rows %>%
    left_join(range_rows, by = "component") %>%
    mutate(
      jobs_not_feasible_without_recharging = coalesce(jobs_not_feasible_without_recharging, 0),
      recharge_vehicles_added = coalesce(recharge_vehicles_added, 0),
      services_without_recharge_fallback = coalesce(services_without_recharge_fallback, 0),
      share_not_feasible_without_recharging = safe_divide(jobs_not_feasible_without_recharging, jobs_shipments),
      row_type = "component",
      source_file = paste_source_columns(jobs_source_file, range_source_file)
    )

  total <- component_rows %>%
    summarise(
      component = "reported subtotal",
      jobs_shipments = safe_sum(jobs_shipments),
      jobs_not_feasible_without_recharging = safe_sum(jobs_not_feasible_without_recharging),
      recharge_vehicles_added = safe_sum(recharge_vehicles_added),
      services_without_recharge_fallback = safe_sum(services_without_recharge_fallback),
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
      jobs_shipments = round(jobs_shipments, 0),
      jobs_not_feasible_without_recharging = round(jobs_not_feasible_without_recharging, 0),
      share_not_feasible_without_recharging = round(share_not_feasible_without_recharging, 4),
      recharge_vehicles_added = round(recharge_vehicles_added, 0),
      services_without_recharge_fallback = round(services_without_recharge_fallback, 0),
      source_file
    )
}

build_paper_table_ltl_recharge_vehicle_use <- function(jobs_per_tour, person_scores, paths) {
  if (paths$fleet_type != "EV" || nrow(jobs_per_tour) == 0) {
    return(tibble())
  }

  person_lookup <- build_person_lookup(person_scores, c("person", "subpopulation", "goods_type"))
  rows <- jobs_per_tour %>%
    transmute(
      person = as.character(personId),
      vehicle_type = as.character(vehicleType),
      jobs_per_tour = as_number(jobsPerTour)
    ) %>%
    left_join(person_lookup, by = "person") %>%
    mutate(
      component = paper_component_from_trip(subpopulation, goods_type),
      is_recharge_vehicle = coalesce(is_recharge_vehicle_type(vehicle_type), FALSE)
    ) %>%
    filter(component %in% c("wasteCollection", "CEP", "remainingLTL"))
  if (nrow(rows) == 0) {
    return(tibble())
  }

  component_rows <- rows %>%
    group_by(component) %>%
    summarise(
      assigned_tours = n_distinct(person),
      recharge_vehicle_tours = n_distinct(person[is_recharge_vehicle]),
      assigned_jobs = safe_sum(jobs_per_tour),
      jobs_on_recharge_vehicle_tours = safe_sum(ifelse(is_recharge_vehicle, jobs_per_tour, 0)),
      .groups = "drop"
    ) %>%
    mutate(row_type = "component")

  total <- component_rows %>%
    summarise(
      component = "Explicit LTL total",
      assigned_tours = safe_sum(assigned_tours),
      recharge_vehicle_tours = safe_sum(recharge_vehicle_tours),
      assigned_jobs = safe_sum(assigned_jobs),
      jobs_on_recharge_vehicle_tours = safe_sum(jobs_on_recharge_vehicle_tours),
      .groups = "drop"
    ) %>%
    mutate(row_type = "total")

  bind_rows(component_rows, total) %>%
    mutate(
      recharge_vehicle_tour_share = safe_divide(recharge_vehicle_tours, assigned_tours),
      jobs_on_recharge_vehicle_tour_share = safe_divide(jobs_on_recharge_vehicle_tours, assigned_jobs)
    ) %>%
    transmute(
      scenario_name = paths$scenario_name,
      demand_generation = paths$model_type,
      vehicle_type = paper_vehicle_type_label(paths$fleet_type),
      sample = paste0(paths$sample_tag, "%"),
      row_type,
      component,
      assigned_tours = round(assigned_tours, 0),
      recharge_vehicle_tours = round(recharge_vehicle_tours, 0),
      recharge_vehicle_tour_share = round(recharge_vehicle_tour_share, 4),
      assigned_jobs = round(assigned_jobs, 0),
      jobs_on_recharge_vehicle_tours = round(jobs_on_recharge_vehicle_tours, 0),
      jobs_on_recharge_vehicle_tour_share = round(jobs_on_recharge_vehicle_tour_share, 4),
      source_file = "analysis/commercialTraffic/tourAnalysis_jobsPerTour.csv;output_persons.csv"
    )
}

build_paper_table_mixed_ltl_fleet_composition <- function(carrier_time_distance_vehicle_type, paths) {
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
  ltl_rows <- prepare_mixed_ltl_vehicle_type_rows(carrier_time_distance_vehicle_type, paths)
  if (nrow(ltl_rows) == 0) {
    return(tibble())
  }

  factor_year <- resolve_emission_factor_year(paths$year)
  intensity_col <- paste0("intensity_kg_co2e_per_100km_", factor_year)

  emissions <- ltl_rows %>%
    mutate(
      intensity_kg_co2e_per_100km = .data[[intensity_col]],
      wtw_emissions_kt_year = total_distance_km * workdays_per_year * intensity_kg_co2e_per_100km / 100 / 1000
    )

  component_rows <- emissions %>%
    group_by(component) %>%
    summarise(
      wtw_emissions_kt_year = safe_sum(wtw_emissions_kt_year),
      source_file = collapse_source_values(source_file),
      .groups = "drop"
    ) %>%
    mutate(row_type = "component")

  total <- component_rows %>%
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
      emission_factor_year = factor_year,
      sample = paste0(paths$sample_tag, "%"),
      row_type,
      component,
      wtw_emissions_kt_year = round(wtw_emissions_kt_year, 1),
      source_file
    )
}

prepare_mixed_ltl_vehicle_type_rows <- function(carrier_time_distance_vehicle_type, paths) {
  if (paths$fleet_type != "Mixed" || nrow(carrier_time_distance_vehicle_type) == 0) {
    return(tibble())
  }

  carrier_time_distance_vehicle_type %>%
    mutate(
      component = paper_component_from_carrier_component(component),
      vehicles = as_number(nuofvehicles),
      total_distance_km = coalesce(
        if ("sumoftraveldistances_km" %in% names(.)) as_number(.data$sumoftraveldistances_km) else NA_real_,
        if ("sumoftraveldistances_m" %in% names(.)) as_number(.data$sumoftraveldistances_m) / 1000 else NA_real_
      ),
      total_cost_eur = if ("totalcosts_eur" %in% names(.)) as_number(.data$totalcosts_eur) else NA_real_
    ) %>%
    filter(component %in% c("wasteCollection", "CEP", "remainingLTL")) %>%
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

# =============================================================================
# 6. Fahrzeugparameter und WTW-Emissionsschaetzung
# =============================================================================

# Lists files only when the directory exists, avoiding noisy warnings for optional price-year folders.
list_files_if_dir <- function(path, pattern, recursive = FALSE) {
  if (!dir.exists(path)) {
    return(character())
  }

  list.files(path, pattern = pattern, recursive = recursive, full.names = TRUE)
}

# Finds the vehicle-type XML used for either the selected run year or an alternate price year.
find_vehicle_types_file <- function(paths, cost_year = paths$year) {
  cost_year <- as.integer(cost_year)
  override <- cost_vehicle_type_file_override(cost_year)
  if (!is.na(override)) {
    return(override)
  }

  scenario_name_for_cost_year <- sub("_[0-9]{4}$", paste0("_", cost_year), paths$scenario_name)
  cost_scenario_dir <- file.path(paths$study_dir, scenario_name_for_cost_year)
  cost_sample_dir <- file.path(cost_scenario_dir, basename(paths$sample_dir))
  cost_year_dir <- file.path(paths$study_dir, as.character(cost_year))
  scenarios_dir <- file.path(dirname(dirname(paths$study_dir)), "scenarios")
  config_candidates <- vehicle_type_candidates_from_run_configs(paths, cost_sample_dir)

  current_run_candidates <- if (cost_year == as.integer(paths$year)) {
    c(
      vehicle_type_candidates_in_dir(paths$sample_dir),
      vehicle_type_candidates_in_dir(paths$generated_input_dir),
      vehicle_type_candidates_in_dir(paths$scenario_dir)
    )
  } else {
    character()
  }

  candidates <- c(
    current_run_candidates,
    vehicle_type_candidates_in_dir(cost_sample_dir),
    vehicle_type_candidates_in_dir(file.path(cost_sample_dir, paths$final_run_name)),
    vehicle_type_candidates_in_dir(file.path(cost_sample_dir, basename(paths$final_run_dir))),
    vehicle_type_candidates_in_dir(file.path(cost_scenario_dir, "generatedInputData")),
    vehicle_type_candidates_in_dir(cost_scenario_dir),
    config_candidates,
    vehicle_type_candidates_from_shared_inputs(paths, cost_year),
    vehicle_type_candidates_in_dir(cost_year_dir),
    list_files_if_dir(
      scenarios_dir,
      paste0("mode-vehicles_WV_base", cost_year, "_", paths$fleet_type, "\\.xml(\\.gz)?$"),
      recursive = TRUE
    )
  )

  existing <- candidates[file.exists(candidates)]
  if (length(existing) == 0) {
    return(NA_character_)
  }

  normalizePath(existing[[1]], winslash = "/", mustWork = TRUE)
}

# Searches shared input repositories for vehicle-type files matching the requested price year and fleet.
vehicle_type_candidates_from_shared_inputs <- function(paths, cost_year) {
  shared_root <- dirname(dirname(dirname(paths$study_dir)))
  search_roots <- unique(c(
    file.path(shared_root, "runs-svn"),
    file.path(shared_root, "matsim-metropole-ruhr")
  ))
  search_roots <- search_roots[dir.exists(search_roots)]
  if (length(search_roots) == 0) {
    return(character())
  }

  pattern <- paste0("mode-vehicles.*base", as.integer(cost_year), "_", paths$fleet_type, "\\.xml(\\.gz)?$")
  unique(unlist(lapply(search_roots, function(root) {
    list_files_if_dir(root, pattern, recursive = TRUE)
  }), use.names = FALSE))
}

# Resolves vehicle-type file references from MATSim output_config.xml files in a price-year run.
vehicle_type_candidates_from_run_configs <- function(paths, cost_sample_dir) {
  if (!dir.exists(cost_sample_dir)) {
    return(character())
  }

  run_dirs <- unique(c(
    file.path(cost_sample_dir, paths$final_run_name),
    file.path(cost_sample_dir, paste0("commercialTraffic_Run", paths$sample_tag, "pct")),
    list.dirs(cost_sample_dir, recursive = FALSE, full.names = TRUE)
  ))
  run_dirs <- run_dirs[dir.exists(run_dirs)]
  if (length(run_dirs) == 0) {
    return(character())
  }

  config_files <- unique(unlist(lapply(run_dirs, function(run_dir) {
    list_files_if_dir(run_dir, "output_config\\.xml$")
  }), use.names = FALSE))
  if (length(config_files) == 0) {
    return(character())
  }

  unique(unlist(lapply(config_files, function(config_file) {
    refs <- extract_config_vehicle_file_refs(config_file)
    unlist(lapply(refs, resolve_config_vehicle_file_ref, config_file = config_file, paths = paths), use.names = FALSE)
  }), use.names = FALSE))
}

# Extracts non-null vehiclesFile references from a MATSim output_config.xml.
extract_config_vehicle_file_refs <- function(config_file) {
  lines <- tryCatch(readLines(config_file, warn = FALSE), error = function(e) character())
  if (length(lines) == 0) {
    return(character())
  }

  refs <- str_match(lines, '<param name="vehiclesFile" value="([^"]+)"')[, 2]
  refs <- refs[!is.na(refs) & refs != "null" & nzchar(refs)]
  unique(refs)
}

# Resolves one config vehiclesFile reference against nearby output and input roots.
resolve_config_vehicle_file_ref <- function(ref, config_file, paths) {
  ref <- normalize_user_path(ref)
  direct_candidates <- if (grepl("^[A-Za-z]:/", ref) || startsWith(ref, "/")) {
    ref
  } else {
    c(
      file.path(dirname(config_file), ref),
      file.path(dirname(dirname(config_file)), ref),
      file.path(paths$sample_dir, ref),
      file.path(paths$scenario_dir, ref),
      file.path(paths$study_dir, ref),
      file.path(dirname(paths$study_dir), ref),
      file.path(dirname(dirname(paths$study_dir)), ref)
    )
  }

  shared_root <- dirname(dirname(dirname(paths$study_dir)))
  search_roots <- unique(c(
    file.path(shared_root, "runs-svn"),
    file.path(shared_root, "matsim-metropole-ruhr")
  ))
  search_roots <- search_roots[dir.exists(search_roots)]
  searched_candidates <- find_named_file_in_roots(basename(ref), search_roots)

  unique(c(direct_candidates, searched_candidates))
}

# Searches for a named file, including an optional .gz variant, in selected roots.
find_named_file_in_roots <- function(file_name, roots) {
  if (length(roots) == 0 || is.na(file_name) || !nzchar(file_name)) {
    return(character())
  }

  pattern <- paste0("^", escape_regex(file_name), "(\\.gz)?$")
  unique(unlist(lapply(roots, function(root) {
    list_files_if_dir(root, pattern, recursive = TRUE)
  }), use.names = FALSE))
}

# Escapes a literal string for use as a regular expression.
escape_regex <- function(x) {
  gsub("([][{}()+*^$|\\\\?.])", "\\\\\\1", x, perl = TRUE)
}

# Returns an explicit price-year vehicle-type file from the manual override block, if configured.
cost_vehicle_type_file_override <- function(cost_year) {
  if (!exists("cost_vehicle_type_file_overrides", inherits = TRUE)) {
    return(NA_character_)
  }

  overrides <- get("cost_vehicle_type_file_overrides", inherits = TRUE)
  if (length(overrides) == 0 || is.null(names(overrides))) {
    return(NA_character_)
  }

  selected <- overrides[[as.character(as.integer(cost_year))]]
  if (is.null(selected) || is.na(selected) || !nzchar(selected)) {
    return(NA_character_)
  }

  selected <- normalize_user_path(selected)
  if (!file.exists(selected)) {
    add_warning("Configured cost vehicle-type override does not exist for ", cost_year, ": ", selected)
    return(NA_character_)
  }

  normalizePath(selected, winslash = "/", mustWork = TRUE)
}

# Returns vehicle-type XML candidates in a directory in preference order.
vehicle_type_candidates_in_dir <- function(directory) {
  if (!dir.exists(directory)) {
    return(character())
  }

  c(
    file.path(directory, "mode-vehicles_withKwmCarrierVehicleTypes.xml.gz"),
    file.path(directory, "mode-vehicles_withKwmCarrierVehicleTypes.xml"),
    file.path(directory, "mode-vehicles.xml.gz"),
    file.path(directory, "mode-vehicles.xml"),
    list_files_if_dir(directory, "mode-vehicles.*xml(\\.gz)?$")
  )
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
      vehicle_type_base = normalize_vehicle_type(vehicle_type),
      is_recharge_vehicle_type = is_recharge_vehicle_type(vehicle_type),
      recharge_level = recharge_level(vehicle_type),
      usable_range_km = safe_divide(energy_capacity_kwh_or_liters, energy_consumption_kwh_per_meter) / 1000,
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
    description = extract_tag_text(text, "description"),
    seats = as_number(extract_attr_from_text(text, "capacity", "seats")),
    standing_room = as_number(extract_attr_from_text(text, "capacity", "standingRoomInPersons")),
    payload_capacity = as_number(extract_attr_from_text(text, "capacity", "other")),
    length_m = as_number(extract_attr_from_text(text, "length", "meter")),
    width_m = as_number(extract_attr_from_text(text, "width", "meter")),
    maximum_velocity_mps = as_number(extract_attr_from_text(text, "maximumVelocity", "meterPerSecond")),
    fixed_costs_per_day = as_number(extract_attr_from_text(text, "costInformation", "fixedCostsPerDay")),
    costs_per_meter = as_number(extract_attr_from_text(text, "costInformation", "costsPerMeter")),
    costs_per_second = as_number(extract_attr_from_text(text, "costInformation", "costsPerSecond")),
    pce = as_number(extract_attr_from_text(text, "passengerCarEquivalents", "pce")),
    network_mode = extract_attr_from_text(text, "networkMode", "networkMode"),
    hbefa_technology = extract_named_attribute(text, "HbefaTechnology"),
    hbefa_vehicle_category = extract_named_attribute(text, "HbefaVehicleCategory"),
    energy_capacity_kwh_or_liters = as_number(extract_named_attribute(text, "energyCapacityInKWhOrLiters")),
    energy_consumption_kwh_per_meter = as_number(extract_named_attribute(text, "energyConsumptionKWhPerMeter")),
    costs_per_second_in_service = as_number(extract_named_attribute(text, "costsPerSecondInService")),
    costs_per_second_waiting = as_number(extract_named_attribute(text, "costsPerSecondWaiting"))
  )
}

extract_attr_from_text <- function(text, tag, attr) {
  pattern <- paste0("<", tag, "[^>]*\\s", attr, '="([^"]*)"')
  match <- str_match(text, pattern)[, 2]
  ifelse(is.na(match), NA_character_, match)
}

extract_tag_text <- function(text, tag) {
  pattern <- paste0("<", tag, "[^>]*>(.*?)</", tag, ">")
  match <- str_match(text, pattern)[, 2]
  ifelse(is.na(match), NA_character_, trimws(match))
}

extract_named_attribute <- function(text, attribute_name) {
  pattern <- paste0('<attribute name="', attribute_name, '"[^>]*>(.*?)</attribute>')
  match <- str_match(text, pattern)[, 2]
  ifelse(is.na(match), NA_character_, trimws(match))
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

resolve_emission_factor_year <- function(result_year) {
  if (as.integer(result_year) == 2024L) {
    return(2021L)
  }

  if (!as.integer(result_year) %in% c(2021L, 2030L, 2050L)) {
    add_warning("Unsupported emission_result_year ", result_year, "; using 2021 factors.")
    return(2021L)
  }

  as.integer(result_year)
}

build_energy_emissions <- function(tour_distances, paths) {
  if (nrow(tour_distances) == 0) {
    return(tibble())
  }

  factor_year <- resolve_emission_factor_year(emission_result_year)
  intensity_col <- paste0("intensity_kg_co2e_per_100km_", factor_year)

  result <- tour_distances %>%
    mutate(
      groupOfSubpopulation = clean_subpopulation(.data$groupOfSubpopulation),
      vehicleType = as.character(.data$vehicleType),
      vehicle_type_base = normalize_vehicle_type(vehicleType),
      distanceInKm = as_number(.data$distanceInKm)
    ) %>%
    group_by(groupOfSubpopulation, vehicleType, vehicle_type_base) %>%
    summarise(
      tours_sample = n(),
      vehicle_km_sample_day = safe_sum(distanceInKm),
      vehicle_km_sample_year = vehicle_km_sample_day * workdays_per_year,
      vehicle_km_100pct_day = vehicle_km_sample_day * paths$sample_factor_to_100pct,
      vehicle_km_100pct_year = vehicle_km_100pct_day * workdays_per_year,
      .groups = "drop"
    ) %>%
    left_join(vehicle_type_to_key, by = "vehicle_type_base") %>%
    left_join(vehicle_intensities, by = "vehicle_key") %>%
    mutate(
      requested_emission_year = emission_result_year,
      emission_factor_year = factor_year,
      intensity_kg_co2e_per_100km = .data[[intensity_col]],
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
    with_meta(paths, "analysis/commercialTraffic/tourAnalysis_distances.csv")

  unknown <- result %>%
    filter(is.na(vehicle_key)) %>%
    distinct(vehicle_type_base) %>%
    pull(vehicle_type_base)

  if (length(unknown) > 0) {
    add_warning("No WTW mapping for vehicle type(s): ", paste(unknown, collapse = ", "))
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
    grepl("commercialPersonTraffic", text, ignore.case = TRUE) ~ "commercialPersonTraffic",
    grepl("goodsTraffic", text, ignore.case = TRUE) ~ "smallScaleGoodsTraffic",
    grepl("PARCEL", text, ignore.case = TRUE) ~ "LTL_Parcel",
    grepl("WASTE", text, ignore.case = TRUE) ~ "LTL_Waste",
    grepl("REST", text, ignore.case = TRUE) ~ "LTL_Rest",
    grepl("FTL", text, ignore.case = TRUE) ~ "FTL",
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
  time_distance <- read_carrier_time_distance(aggregate_dirs, paths)

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

    data %>%
      normalize_names() %>%
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

read_range_recharge_logs <- function(paths) {
  roots <- c(paths$small_scale_dir, paths$slurm_log_dir)
  roots <- roots[dir.exists(roots)]
  log_files <- unlist(lapply(roots, function(root) {
    list.files(root, pattern = "\\.log$", recursive = TRUE, full.names = TRUE)
  }), use.names = FALSE)

  if (length(log_files) == 0) {
    return(tibble())
  }

  purrr::map_dfr(log_files, function(path) {
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
      no_feasible_recharge_fallback_services = sum(extract_numeric_from_lines(lines, "([0-9]+) services had no feasible Recharge fallback")),
      no_feasible_recharge_fallback_warning_lines = sum(grepl("no feasible Recharge fallback", lines)),
      restored_recharge_cost_capacity_lines = sum(grepl("Restored costs and capacities", lines)),
      max_required_multiplier_applications = ifelse(length(required_multiplier) == 0, NA_real_, max(required_multiplier, na.rm = TRUE)),
      max_current_range_utilization_pct = ifelse(length(current_range_util) == 0, NA_real_, max(current_range_util, na.rm = TRUE))
    ) %>%
      with_meta(paths, path)
  })
}

extract_numeric_from_lines <- function(lines, pattern) {
  matches <- str_match(lines, pattern)[, 2]
  as_number(matches[!is.na(matches)])
}

# =============================================================================
# 8. Ausfuehrung
# =============================================================================

main()
