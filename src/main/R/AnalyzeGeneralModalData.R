library(readr)
library(dplyr)
library(ggplot2)
library(tidyr)
library(scales)

# CSV einlesen – angepasst für Semikolon-Trennung und DezimalPUNKT
setwd("C:/Users/erica/shared/matsim-metropole-ruhr/scenarios/metropole-ruhr-v2024.0/output/rvr/commercial_0.1pct/commercialTraffic_Run0pct/")
setwd("C:/Users/erica/shared/matsim-metropole-ruhr/scenarios/metropole-ruhr-v2024.0/output/rvr/commercial_10pct/commercialTraffic_Run10pct/")
setwd("C:/Users/erica/shared/matsim-metropole-ruhr/scenarios/metropole-ruhr-v2024.0/output/009_1pct/")
data <- read_delim("/analysis/traffic/commercialTraffic_Run0pct.generalTravelData.csv",
                   delim = ";",
                   locale = locale(decimal_mark = "."))

tours <- read_delim("/analysis/traffic/009.tour_durations.csv",
                   delim = ";",
                   locale = locale(decimal_mark = "."))

distancesPerTour <- read_delim("/analysis/traffic/009.travelDistances_perVehicle.csv",
                    delim = ";",
                    locale = locale(decimal_mark = "."))


data$subpopulation <- ifelse(data$subpopulation %in% c("commercialPersonTraffic", "commercialPersonTraffic_service"), "Personenwirtschaftsverkehr", data$subpopulation)
data$subpopulation <- ifelse(data$subpopulation %in% c("FTL_kv_trip", "FTL_trip"), "FTL", data$subpopulation)
data$subpopulation <- ifelse(data$subpopulation %in% c("LTL_trips"), "LTL", data$subpopulation)
data$subpopulation <- ifelse(data$subpopulation %in% c("goodsTraffic"), "kleinräumiger WV", data$subpopulation)
data$subpopulation <- ifelse(data$subpopulation %in% c("longDistanceFreight"), "Transit-GV", data$subpopulation)

tours$subpopulation <- ifelse(tours$subpopulation %in% c("commercialPersonTraffic", "commercialPersonTraffic_service"), "Personenwirtschaftsverkehr", tours$subpopulation)
tours$subpopulation <- ifelse(tours$subpopulation %in% c("FTL_kv_trip", "FTL_trip"), "FTL", tours$subpopulation)
tours$subpopulation <- ifelse(tours$subpopulation %in% c("LTL_trips"), "LTL", tours$subpopulation)
tours$subpopulation <- ifelse(tours$subpopulation %in% c("goodsTraffic"), "kleinräumiger WV", tours$subpopulation)
tours$subpopulation <- ifelse(tours$subpopulation %in% c("longDistanceFreight"), "Transit-GV", tours$subpopulation)

distancesPerTour$subpopulation <- ifelse(distancesPerTour$subpopulation %in% c("commercialPersonTraffic", "commercialPersonTraffic_service"), "Personenwirtschaftsverkehr", distancesPerTour$subpopulation)
distancesPerTour$subpopulation <- ifelse(distancesPerTour$subpopulation %in% c("FTL_kv_trip", "FTL_trip"), "FTL", distancesPerTour$subpopulation)
distancesPerTour$subpopulation <- ifelse(distancesPerTour$subpopulation %in% c("LTL_trips"), "LTL", distancesPerTour$subpopulation)
distancesPerTour$subpopulation <- ifelse(distancesPerTour$subpopulation %in% c("goodsTraffic"), "kleinräumiger WV", distancesPerTour$subpopulation)
distancesPerTour$subpopulation <- ifelse(distancesPerTour$subpopulation %in% c("longDistanceFreight"), "Transit-GV", distancesPerTour$subpopulation)


# Numerische Spalten korrekt umwandeln
data <- data %>%
  mutate(across(-subpopulation, as.numeric))

# Distanzen in Kilometer umrechnen
distance_cols <- grep("Distance", names(data), value = TRUE)
data <- data %>%
  mutate(across(all_of(distance_cols), ~ .x / 1000))

# Plot 1: Anzahl Agenten je Subpopulation
ggplot(data, aes(x = subpopulation, y = numberOfAgents)) +
  geom_col(fill = "steelblue") +
  geom_text(aes(label = numberOfAgents), vjust = -0.3, size = 3.5) +
  theme_minimal() +
  labs(title = "Anzahl Agenten je Subpopulation mit LinkBased Trips",
       x = "Subpopulation",
       y = "Anzahl Agenten") +
  theme(axis.text.x = element_text(angle = 45, hjust = 1))

# Plot 2: Gefahrene Strecke (km) je Subpopulation
ggplot(data, aes(x = subpopulation, y = traveledDistance_all)) +
  geom_col(fill = "darkgreen") +
  geom_text(aes(label = round(traveledDistance_all, 1)), vjust = -0.3, size = 3.5) +
  theme_minimal() +
  labs(title = "Gefahrene Gesamtstrecke je Subpopulation",
       x = "Subpopulation",
       y = "Strecke (km)") +
  theme(axis.text.x = element_text(angle = 45, hjust = 1))

# Plot 2-2: Gefahrene Strecke (km) je Subpopulation im Untersuchungsgebiet
ggplot(data, aes(x = subpopulation, y = traveledDistanceInRVR_area_all)) +
  geom_col(fill = "darkgreen") +
  geom_text(aes(label = round(traveledDistanceInRVR_area_all, 1)), vjust = -0.3, size = 3.5) +
  theme_minimal() +
  labs(title = "Gefahrene Gesamtstrecke je Subpopulation im Untersuchungsgebiet",
       x = "Subpopulation",
       y = "Strecke (km)") +
  theme(axis.text.x = element_text(angle = 45, hjust = 1))

# Plot 3: Durchschnittliche Trips pro Agent
ggplot(data, aes(x = subpopulation, y = averageTripsPerAgent_all)) +
  geom_col(fill = "tomato") +
  geom_text(aes(label = round(averageTripsPerAgent_all, 2)), vjust = -0.3, size = 3.5) +
  theme_minimal() +
  labs(title = "Ø Anzahl Trips je Agent",
       x = "Subpopulation",
       y = "Ø Trips pro Agent") +
  theme(axis.text.x = element_text(angle = 45, hjust = 1))

# Summiere die Fahrweiten über alle Subpopulationen hinweg
total_distances <- data %>%
  summarise(
    Intern = sum(traveledDistanceInRVR_area_Intern, na.rm = TRUE),
    Incoming = sum(traveledDistanceInRVR_area_Incoming, na.rm = TRUE),
    Outgoing = sum(traveledDistanceInRVR_area_Outgoing, na.rm = TRUE),
    Transit = sum(traveledDistanceInRVR_area_Transit, na.rm = TRUE)
  ) %>%
  pivot_longer(cols = everything(), names_to = "Kategorie", values_to = "Strecke_km") %>%
  mutate(subpopulation = "Gesamt", Typ = "Innerhalb Ruhrgebiet")

# Plot 4: Gesamte Fahrweiten je Kategorie
ggplot(total_distances, aes(x = Kategorie, y = Strecke_km)) +
  geom_col(fill = "orchid") +
  geom_text(aes(label = round(Strecke_km, 1)), vjust = -0.3, size = 4) +
  theme_minimal() +
  labs(title = "Gesamte Fahrweite innerhalb des Ruhrgebiets nach Kategorie",
       x = "Fahrkategorie",
       y = "Strecke (km)") +
  theme(axis.text.x = element_text(angle = 0))

# 5. Plot: Gruppiertes Balkendiagramm mit Facetten je Subpopulation

# 1. Datensatz für RVR-Strecken
dist_rvr <- data %>%
  select(subpopulation,
         Intern = traveledDistanceInRVR_area_Intern,
         Incoming = traveledDistanceInRVR_area_Incoming,
         Outgoing = traveledDistanceInRVR_area_Outgoing,
         Transit = traveledDistanceInRVR_area_Transit) %>%
  pivot_longer(-subpopulation, names_to = "Kategorie", values_to = "Strecke_km") %>%
  mutate(Typ = "Innerhalb Ruhrgebiet")

# 3. Zusammenführen
combined <- bind_rows(dist_rvr, total_distances)

# 5. Plot: Gruppiertes Balkendiagramm mit Facetten je Subpopulation
ggplot(combined, aes(x = Kategorie, y = Strecke_km, fill = Typ)) +
  geom_col(position = "dodge") +
  geom_text(aes(label = comma(Strecke_km, accuracy = 1)),
            position = position_dodge(width = 0.9),
            vjust = -0.3, size = 3) +
  facet_wrap(~ subpopulation) +
  theme_minimal() +
  labs(title = "Fahrweiten je Kategorie und Subpopulation",
       x = "Fahrkategorie",
       y = "Strecke (km)",
       fill = "Typ") +
  scale_fill_manual(values = c("Innerhalb Ruhrgebiet" = "deepskyblue")) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1),
    panel.border = element_rect(color = "black", fill = NA, linewidth = 0.5)  # <-- Rahmen
  )

# 1. Datensatz für Subpopulationen – Durchschnittliche Distanzen
avg_dist <- data %>%
  select(subpopulation,
         Intern = averageDistancePerTrip_Intern,
         Incoming = averageDistancePerTrip_Incoming,
         Outgoing = averageDistancePerTrip_Outgoing,
         Transit = averageDistancePerTrip_Transit) %>%
  pivot_longer(-subpopulation, names_to = "Kategorie", values_to = "Durchschnitt_km") %>%
  mutate(Typ = "Ø pro Trip")

# 2. Aufsummierter Durchschnittswert über alle Subpopulationen (gewichtetes Mittel wäre korrekt – hier: einfacher Mittelwert)
avg_dist_total <- data %>%
  summarise(
    Intern = mean(averageDistancePerTrip_Intern, na.rm = TRUE),
    Incoming = mean(averageDistancePerTrip_Incoming, na.rm = TRUE),
    Outgoing = mean(averageDistancePerTrip_Outgoing, na.rm = TRUE),
    Transit = mean(averageDistancePerTrip_Transit, na.rm = TRUE)
  ) %>%
  pivot_longer(cols = everything(), names_to = "Kategorie", values_to = "Durchschnitt_km") %>%
  mutate(subpopulation = "Gesamt", Typ = "Ø pro Trip")

# 3. Zusammenführen
avg_combined <- bind_rows(avg_dist, avg_dist_total)

# 4. Plot
ggplot(avg_combined, aes(x = Kategorie, y = Durchschnitt_km, fill = Typ)) +
  geom_col(position = "dodge") +
  geom_text(aes(label = comma(Durchschnitt_km, accuracy = 1)),
            position = position_dodge(width = 0.9),
            vjust = -0.3, size = 3) +
  facet_wrap(~ subpopulation) +
  theme_minimal() +
  labs(title = "Durchschnittliche TripDistance je Kategorie und Subpopulation",
       x = "Fahrkategorie",
       y = "Durchschnittliche Strecke (km)",
       fill = "Typ") +
  scale_fill_manual(values = c("Ø pro Trip" = "darkorange")) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1),
    panel.border = element_rect(color = "black", fill = NA, linewidth = 0.5)
  )


########################## Tour Dauern ##########################
# 2. Tourdauer in Minuten umrechnen
tours <- tours %>%
  mutate(tourDurationMinutes = tourDurationInSeconds / 60)

# Konfigurierbare Bin-Größe
bin_width <- 60  # z. B. 30-Minuten-Intervalle

# Robuste Erstellung der Bins
max_min <- ceiling(max(tours$tourDurationMinutes, na.rm = TRUE))
breaks <- seq(0, max_min + bin_width, by = bin_width)

# Labels automatisch erzeugen
labels <- paste0(head(breaks, -1), "–", tail(breaks, -1))

# Binning mit cut()
tours <- tours %>%
  mutate(duration_bin = cut(tourDurationMinutes,
                            breaks = breaks,
                            labels = labels,
                            include.lowest = TRUE,
                            right = FALSE))

# Histogramm mit Facetten je Subpopulation
ggplot(tours, aes(x = duration_bin)) +
  geom_bar(fill = "steelblue") +
  facet_wrap(~ subpopulation) +
  theme_minimal() +
  labs(title = "Tourdauern je Subpopulation",
       x = "Tourdauer (Minuten)",
       y = "Anzahl Touren") +
  theme(axis.text.x = element_text(angle = 45, hjust = 1)) +
  scale_y_continuous(labels = comma)


########################## Fahrtweiten ##########################

# Bin-Größe und manuelle Breaks
bin_width <- 10
breaks <- c(0,10,20,30,40,50,60,75,90,105,120,150,180,240,300,420,540,660,780,900,Inf)
labels <- paste0(head(breaks, -1), "–", tail(breaks, -1))

# Filtern und Binning
distancesPerTour <- distancesPerTour %>%
  filter(subpopulation != "person") %>%
  mutate(
    distance_bin = cut(distanceInKm, breaks = breaks, labels = labels, include.lowest = TRUE, right = FALSE)
  )

# Konfigurierbare Bin-Größe

# Histogramm mit Facetten je Subpopulation
ggplot(distancesPerTour, aes(x = distance_bin)) +
  geom_bar(fill = "steelblue") +
  facet_wrap(~ subpopulation) +
  theme_minimal() +
  labs(title = "Fahrweiten je Tour je Subpopulation",
       x = "Fahrweiten (km)",
       y = "Anzahl Touren") +
  theme(axis.text.x = element_text(angle = 45, hjust = 1)) +
  scale_y_continuous(labels = comma)




# Prozentuale Anteile berechnen
bin_counts <- distancesPerTour %>%
  group_by(subpopulation, distance_bin) %>%
  summarise(count = n(), .groups = "drop") %>%
  group_by(subpopulation) %>%
  mutate(
    percentage = count / sum(count) * 100
  )

# Mittelwerte berechnen
means <- distancesPerTour %>%
  group_by(subpopulation) %>%
  summarise(mean_distance = mean(distanceInKm, na.rm = TRUE))

# Plot
ggplot(bin_counts, aes(x = distance_bin, y = percentage)) +
  geom_col(fill = "steelblue") +
  facet_wrap(~ subpopulation) +
  geom_text(aes(label = paste0(round(percentage, 1), "%")),
            vjust = -0.3, size = 3) +
  geom_text(
    data = means,
    aes(x = 1, y = Inf, label = paste0("Ø ", round(mean_distance, 1), " km")),
    vjust = 1.5, hjust = 0, size = 3.5,
    inherit.aes = FALSE
  ) +
  theme_minimal() +
  labs(
    title = "Fahrweiten je Tour je Subpopulation (in %) im Modell",
    x = "Fahrweiten (km)",
    y = "Anteil der Touren (%)"
  ) +
  theme(
    axis.text.x = element_text(angle = 45, hjust = 1)
  ) +
  scale_y_continuous(labels = percent_format(scale = 1))  # % statt Dezimal