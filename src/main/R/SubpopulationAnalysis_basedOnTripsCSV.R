library(tidyverse)
library(dplyr)
library(patchwork)
library(networkD3)
library(sf) #=> geography
library(stringr)
library(reshape2)
library(lubridate)

##readTrips
setwd("C:/Users/erica/shared/matsim-metropole-ruhr/")

#1 pct runs
runFolderName <- "commercial_1pct"
runFolderName <- "commercial_1pct_testing"

folder <- paste0("scenarios/metropole-ruhr-v2024.0/output/rvr/", runFolderName ,"/commercialTraffic_Run1pct/commercialTraffic_Run1pct.output_trips.csv.gz")

#10 pct runs

runFolderName <- "commercial_10pct_0.005_ruhrOnly"
runFolderName <- "commercial_10pct_0.005_testing_distance_ruhrOnly"
runFolderName <- "commercial_10pct_testing_3.0_networkRoutes_withFixed_ruhrOnly"
runFolderName <- "commercial_10pct_0.005_reduceHighDensityBy50pct"

runFolderName <- "grav0.10_400iter_bast_-0.2_002_plans_0It_newSimWrapper"
runFolderName <- "grav0.15_400iter_bast_-0.2_002_plans_0It_newSimWrapper"
runFolderName <- "grav0.20_400iter_bast_-0.2_002_plans_0It_newSimWrapper"
runFolderName <- "grav0.25_400iter_bast_-0.2_002_plans_0It_newSimWrapper"
runFolderName <- "grav0.30_400iter_bast_-0.2_002_plans_0It_newSimWrapper"
runFolderName <- "grav0.35_400iter_bast_-0.2_002_plans_0It_newSimWrapper"
runFolderName <- "grav0.40_400iter_bast_-0.2_002_plans_0It_newSimWrapper"
runFolderName <- "grav0.45_400iter_bast_-0.2_002_plans_0It_newSimWrapper"
runFolderName <- "grav0.50_400iter_bast_-0.2_002_plans_0It_newSimWrapper"

runFolderName <- "commercial_10_pct_basicMethods"

runFolderName <- "commercial_10_pct_advanced"
runFolderName_compare <- "commercial_10_pct_advanced"

folder <- paste0("scenarios/metropole-ruhr-v2024.0/output/rvr/", runFolderName ,"/commercialTraffic_Run10pct/commercialTraffic_Run10pct.output_trips.csv.gz")
folder_compare <- paste0("scenarios/metropole-ruhr-v2024.0/output/rvr/", runFolderName_compare ,"/commercialTraffic_Run10pct/commercialTraffic_Run10pct.output_trips.csv.gz")
folder <- paste0("scenarios/metropole-ruhr-v2024.0/output/commercialExperiments_RouteChoiceOnly/", runFolderName, "/commercialExperiment_0It_newSimWrapper.output_trips.csv.gz")

createPlots(folder = folder, runFolderName = runFolderName)
createPlots(folder = folder_compare, runFolderName = runFolderName_compare)

createComparisonPlots(
  folder = folder,
  runFolderName = runFolderName,
  folder_compare = folder_compare,
  runFolderName_compare = runFolderName_compare
)

createPlots <- function(runFolderName, folder) {
  baseCaseTrips <- read_and_prepare(runFolderName, folder)

  ### Agents per Subpopulation
  agents_per_subpop <- baseCaseTrips %>%
    group_by(subpopulation) %>%
    summarise(unique_agents = n_distinct(person)) %>%
    ungroup() %>%
    mutate(share = unique_agents / sum(unique_agents) * 100)

  print(
    ggplot(agents_per_subpop, aes(x = subpopulation, y = unique_agents, fill = subpopulation)) +
      geom_col() +
      geom_text(aes(label = paste0(round(share, 1), "%")),
                vjust = -0.5, size = 4) +
      labs(
        title = paste("Number of agents per subpopulation (Run:", runFolderName, ")"),
        x = "Subpopulation",
        y = "Number of Agents",
        caption = folder
      ) +
      theme_minimal()
  )
  ##Reichweiten
  total_distance_per_subpop <- baseCaseTrips %>%
    group_by(subpopulation) %>%
    summarise(total_distance_km = sum(traveled_distance, na.rm = TRUE) / 1000) %>%
    ungroup() %>%
    mutate(share = total_distance_km / sum(total_distance_km) * 100)

  print(
    ggplot(total_distance_per_subpop, aes(x = subpopulation, y = total_distance_km, fill = subpopulation)) +
      geom_col() +
      geom_text(aes(label = paste0(round(share, 1), "%")),
                vjust = -0.5, size = 4) +
      labs(title = paste("Travelled distance per Subpopulation (Run:", runFolderName, ")"),
           x = "Subpopulation", y = "Travelled distance (km)",
           caption = folder
      ) +
      theme_minimal()
  )
  ##Average  trip distance
  avg_trip_distance <- baseCaseTrips %>%
    group_by(subpopulation) %>%
    summarise(avg_distance_km = mean(traveled_distance, na.rm = TRUE) / 1000) %>%
    ungroup()

  print(
    ggplot(avg_trip_distance, aes(x = subpopulation, y = avg_distance_km, fill = subpopulation)) +
      geom_col() +
      geom_text(aes(label = paste0(round(avg_distance_km, 1), " km")),
                vjust = -0.5, size = 4) +
      labs(title = paste("Average Trip distance per Subpopulation (Run:", runFolderName, ")"),
           x = "Subpopulation", y = "Average Distane (km)",
           caption = folder
      ) +
      theme_minimal()
  )
  ##################Tour distances
  # Tourdistanz und -dauer pro Person berechnen
  tour_distance_per_person <- baseCaseTrips %>%
    group_by(person, subpopulation) %>%
    summarise(
      tour_distance_km = sum(traveled_distance, na.rm = TRUE) / 1000,
      tour_duration_min = sum(travel_time, na.rm = TRUE)
    ) %>%
    ungroup()

  # Durchschnittliche Tourdistanz pro Subpopulation
  avg_tour_distance <- tour_distance_per_person %>%
    group_by(subpopulation) %>%
    summarise(avg_tour_distance_km = mean(tour_distance_km)) %>%
    ungroup()

  # Plot
  print(
    ggplot(avg_tour_distance, aes(x = subpopulation, y = avg_tour_distance_km, fill = subpopulation)) +
      geom_col() +
      geom_text(aes(label = paste0(round(avg_tour_distance_km, 1), " km")),
                vjust = -0.5, size = 4) +
      labs(title = paste("Average tour distance per Subpopulation (Run:", runFolderName, ")"),
           x = "Subpopulation", y = "Ø Travelled distance (km)",
           caption = folder
      ) +
      theme_minimal()
  )
  # Konfigurierbare Bins definieren
  distance_bins <- c(0, 10, 20, 30, 40, 50, 60, 75, 90, 105, 120, 150, 180, 240, 300, 420, 540, 660, 780, 900, Inf)

  #distance_bins <- seq(0, 600, by = 10)     # km
  duration_bins <- seq(0, 600, by = 30)     # Minuten

  # Prozentuale Verteilung der Tourdistanzen

  print(
    tour_distance_per_person %>%
      mutate(distance_bin = cut(tour_distance_km, breaks = distance_bins, include.lowest = TRUE)) %>%
      group_by(subpopulation, distance_bin) %>%
      summarise(n = n()) %>%
      group_by(subpopulation) %>%
      mutate(percentage = n / sum(n) * 100) %>%
      ungroup() %>%
      ggplot(aes(x = distance_bin, y = percentage, fill = subpopulation)) +
      geom_col() +
      facet_wrap(~subpopulation, scales = "free_y") +
      labs(
        title = paste("Prozentuale Verteilung der Tourdistanzen (Run:", runFolderName, ")"),
        x = "Tourdistanz (km)",
        y = "Anteil (%)"
      ) +
      theme_minimal() +
      theme(axis.text.x = element_text(angle = 45, hjust = 1))
  )
  # Prozentuale Verteilung der Tourdauern

  print(
    tour_distance_per_person %>%
      mutate(duration_bin = cut(tour_duration_min, breaks = duration_bins, include.lowest = TRUE)) %>%
      group_by(subpopulation, duration_bin) %>%
      summarise(n = n()) %>%
      group_by(subpopulation) %>%
      mutate(percentage = n / sum(n) * 100) %>%
      ungroup() %>%
      ggplot(aes(x = duration_bin, y = percentage, fill = subpopulation)) +
      geom_col() +
      facet_wrap(~subpopulation, scales = "free_y") +
      labs(
        title = paste("Prozentuale Verteilung der Tourdauern (Run:", runFolderName, ")"),
        x = "Tourdauer (Minuten)",
        y = "Anteil (%)"
      ) +
      theme_minimal() +
      theme(axis.text.x = element_text(angle = 45, hjust = 1))
  )
}

read_and_prepare <- function(runFolderName, folder) {
  df <- read.csv2(folder)

  df %>%
    filter(main_mode %in% c("car", "truck8t", "truck18t", "truck26t", "truck40t")) %>%
    mutate(subpopulation = case_when(
      str_detect(person, "commercialPersonTraffic") ~ "Commercial Person Traffic",
      str_detect(person, "goodsTraffic") ~ "Small Scale Freight Traffic",
      str_detect(person, "freight") & !str_detect(person, "FTL|LTL") ~ "Transit Freight Traffic",
      str_detect(person, "LTL|FTL|GoodsType") ~ "LTL/FTL",
      str_detect(person, "ParcelDelivery") ~ "Parcel Delivery",
      str_detect(person, "WasteCollection") ~ "Waste Collection",
      TRUE ~ "Person Traffic"
    )) %>%
    mutate(
      traveled_distance = as.numeric(gsub(",", ".", traveled_distance)),
      travel_time = as.numeric(lubridate::period_to_seconds(lubridate::hms(trav_time))) / 60,
      run = runFolderName
    )
}

createComparisonPlots <- function(runFolderName, folder, runFolderName_compare, folder_compare) {


  dfA <- read_and_prepare(runFolderName, folder)
  dfB <- read_and_prepare(runFolderName_compare, folder_compare)
  allData <- bind_rows(dfA, dfB)

  # 1️⃣ Anzahl der Agenten
  agents <- allData %>%
    group_by(run, subpopulation) %>%
    summarise(unique_agents = n_distinct(person)) %>%
    group_by(run) %>%
    mutate(share = unique_agents / sum(unique_agents) * 100)

  print(
    ggplot(agents, aes(x = subpopulation, y = unique_agents, fill = run)) +
      geom_col(position = "dodge") +
      geom_text(aes(label = paste0(round(share, 1), "%")), position = position_dodge(width = 0.9), vjust = -0.5) +
      labs(title = "Number of agents per Subpopulation", x = "Subpopulation", y = "Number of agents") +
      theme_minimal()
  )

  # 2️⃣ Gesamte Reiseweiten
  total_dist <- allData %>%
    group_by(run, subpopulation) %>%
    summarise(total_distance_km = sum(traveled_distance, na.rm = TRUE) / 1000) %>%
    group_by(run) %>%
    mutate(share = total_distance_km / sum(total_distance_km) * 100)

  # Summen berechnen pro Run
  sums_per_run <- total_dist %>%
    group_by(run) %>%
    summarise(sum_km = sum(total_distance_km)) %>%
    mutate(label = paste0(run, ": ", round(sum_km, 1), " km"))

  print(
    # ggplot(total_dist, aes(x = subpopulation, y = total_distance_km, fill = run)) +
    #   geom_col(position = "dodge") +
    #   # geom_text(aes(label = paste0(round(total_distance_km, 1), "%")), position = position_dodge(width = 0.9), vjust = -0.5) +
    #   labs(title = "Gesamte Reiseweiten je Subpopulation", x = "Subpopulation", y = "Reiseweite (km)") +
    #   theme_minimal()

    ggplot(total_dist, aes(x = subpopulation, y = total_distance_km, fill = run)) +
      geom_col(position = "dodge") +
      annotate("text", x = 1, y = max(total_dist$total_distance_km) * 1.1,
               label = paste(sums_per_run$label, collapse = "\n"),
               hjust = 0, vjust = 1, size = 3.5) +
      labs(
        title = "Travelled Distances per Subpopulation",
        x = "Subpopulation",
        y = "Travelled Distances (km)"
      ) +
      theme_minimal()
  )

  # 3️⃣ Durchschnittliche Trip-Distanz
  avg_trip <- allData %>%
    group_by(run, subpopulation) %>%
    summarise(avg_distance_km = mean(traveled_distance, na.rm = TRUE) / 1000)

  print(
    ggplot(avg_trip, aes(x = subpopulation, y = avg_distance_km, fill = run)) +
      geom_col(position = "dodge") +
      geom_text(aes(label = paste0(round(avg_distance_km, 1), " km")), position = position_dodge(width = 0.9), vjust = -0.5) +
      labs(title = "Average Trip distance per Subpopulation", x = "Subpopulation", y = "Durchschnitt (km)") +
      theme_minimal()
  )

  # 4️⃣ Durchschnittliche Tourdistanz
  tour_dists <- allData %>%
    group_by(run, person, subpopulation) %>%
    summarise(tour_distance_km = sum(traveled_distance, na.rm = TRUE) / 1000) %>%
    group_by(run, subpopulation) %>%
    summarise(avg_tour_distance_km = mean(tour_distance_km, na.rm = TRUE))

  print(
    ggplot(tour_dists, aes(x = subpopulation, y = avg_tour_distance_km, fill = run)) +
      geom_col(position = "dodge") +
      geom_text(aes(label = paste0(round(avg_tour_distance_km, 1), " km")), position = position_dodge(width = 0.9), vjust = -0.5) +
      labs(title = "Average Trip distance per Subpopulation", x = "Subpopulation", y = "Ø Tourdistanz (km)") +
      theme_minimal()
  )
}




## small scale commercial 
smallScaleCmmercialTrips <-filter(baseCaseTrips, str_detect(baseCaseTrips$subpopulation, pattern = "Personenverkehr") )
numberOfSmallScaleCommercialAgents <- smallScaleCmmercialTrips %>%
  summarise(unique_id_count = n_distinct(person))

totalDistanceTraveledSmallScaleCommercial <- sum(smallScaleCmmercialTrips$traveled_distance)
verkehrsLeistungSmallScaleCommercial <- totalDistanceTraveledSmallScaleCommercial * numberOfSmallScaleCommercialAgents$unique_id_count
verkehrsLeistungSmallScaleCommercial/1000

##personal Trips
personTrips <-filter(baseCaseTrips, !str_detect(baseCaseTrips$person, pattern = "commercialPersonTraffic|goods|freight")) 
numberOfPersonAgents <- personTrips %>%
  summarise(unique_id_count = n_distinct(person))

totalDistanceTraveledPersonAgents <- sum(personTrips$traveled_distance)
verkehrsLeistungPerson <- totalDistanceTraveledPersonAgents * numberOfPersonAgents$unique_id_count
verkehrsLeistungPerson/1000    

##personCarTrips 
personCarTrips <- filter(personTrips, main_mode == "car")
numberOfCarUsingAgents <- summarise(personCarTrips, n_distinct(person))
totalDistanceTraveldByCar <- sum(personCarTrips$traveled_distance)
verkehrsLeistungPrivatePkw <- totalDistanceTraveldByCar * numberOfCarUsingAgents$`n_distinct(person)`
verkehrsLeistungPrivatePkw/1000

gesamtPkwVerkehrsleistung <- verkehrsLeistungPrivatePkw/1000 + verkehrsLeistungSmallScaleCommercial/1000

((verkehrsLeistungSmallScaleCommercial/1000)/ gesamtPkwVerkehrsleistung) *100

