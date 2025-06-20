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

runFolderName <- "commercial_10pct_0.005_newPLansFrom100pct"
folder <- "scenarios/metropole-ruhr-v2024.0/output/rvr/commercial_10pct_0.005_newPLansFrom100pct/commercialTraffic_Run10pct/commercialTraffic_Run10pct.output_trips.csv.gz"

runFolderName <- "commercial_10pct_0.005"
folder <- "scenarios/metropole-ruhr-v2024.0/output/rvr/commercial_10pct_0.005/commercialTraffic_Run10pct/commercialTraffic_Run10pct.output_trips.csv.gz"

runFolderName <- "commercial_10pct_3.0"
folder <- "scenarios/metropole-ruhr-v2024.0/output/rvr/commercial_10pct_3.0/commercialTraffic_Run10pct/commercialTraffic_Run10pct.output_trips.csv.gz"

runFolderName <- "commercial_1pct_testing"
folder <- "scenarios/metropole-ruhr-v2024.0/output/rvr/commercial_1pct_testing/commercialTraffic_Run1pct/commercialTraffic_Run1pct.output_trips.csv.gz"

runID <- "016_10pct"
folder <- "scenarios/metropole-ruhr-v2024.0/output/016_10pct/016.output_trips.csv.gz"


createPlots(folder = folder, runFolderName = runFolderName)

createPlots <- function(runFolderName, folder) {
  baseCaseTrips <- read.csv2(folder)
  ##readShape
  rvrShape <- read_sf("scenarios/metropole-ruhr-v2024.0/input/area/area.shp")

  baseCaseTrips %>%
    filter(!is.na(trav_time) & is.na(as.numeric(gsub(",", ".", trav_time)))) %>%
    select(person, trav_time) %>%
    head()

  # Neue Spalte "subpopulation" extrahieren
  baseCaseTrips <- baseCaseTrips %>%
    filter(main_mode == "car" |
             main_mode == "truck8t" |
             main_mode == "truck18t" |
             main_mode == "truck26t" |
             main_mode == "truck40t") %>%
    mutate(subpopulation = case_when(
      str_detect(person, "commercialPersonTraffic") ~ "Personenwirtschaftsverkehr",
      str_detect(person, "goodsTraffic") ~ "kleinraeumiger WV",
      str_detect(person, "freight|GoodsType") ~ "Güterverkehr",
      str_detect(person, "ParcelDelivery") ~ "KEP",
      str_detect(person, "WasteCollection") ~ "Müllsammlung",
      TRUE ~ "Personenverkehr"
    )) %>%
    mutate(
      traveled_distance = as.numeric(gsub(",", ".", traveled_distance)),
      travel_time = as.numeric(lubridate::period_to_seconds(lubridate::hms(trav_time))) / 60  # Minuten
    )

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
        title = paste("Anzahl der Agenten je Subpopulation (Run:", runFolderName, ")"),
        x = "Subpopulation",
        y = "Anzahl Agenten",
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
      labs(title = paste("Gesamte Reiseweiten je Subpopulation (Run:", runFolderName, ")"),
           x = "Subpopulation", y = "Reiseweite (km)",
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
      geom_text(aes(label = paste0(round(avg_distance_km, 2), " km")),
                vjust = -0.5, size = 4) +
      labs(title = paste("Durchschnittliche Trip-Distanz je Subpopulation (Run:", runFolderName, ")"),
           x = "Subpopulation", y = "Durchschnittliche Distanz (km)",
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
      geom_text(aes(label = paste0(round(avg_tour_distance_km, 2), " km")),
                vjust = -0.5, size = 4) +
      labs(title = paste("Durchschnittliche Tourdistanz je Subpopulation (Run:", runFolderName, ")"),
           x = "Subpopulation", y = "Ø Tourdistanz (km)",
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

