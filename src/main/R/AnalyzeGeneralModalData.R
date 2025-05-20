library(readr)
library(dplyr)
library(ggplot2)
library(tidyr)
library(scales)

# CSV einlesen – angepasst für Semikolon-Trennung und DezimalPUNKT
data <- read_delim("C:/Users/erica/shared/matsim-metropole-ruhr/scenarios/metropole-ruhr-v2024.0/output/rvr/commercial_0.1pct/commercialTraffic_Run0pct/analysis/traffic/commercialTraffic_Run0pct.generalTravelData.csv",
                   delim = ";",
                   locale = locale(decimal_mark = "."))

data <- read_delim("C:/Users/erica/shared/matsim-metropole-ruhr/scenarios/metropole-ruhr-v2024.0/output/rvr/commercial_10pct/commercialTraffic_Run10pct/analysis/traffic/commercialTraffic_Run10pct.generalTravelData.csv",
                   delim = ";",
                   locale = locale(decimal_mark = "."))

data <- read_delim("C:/Users/erica/shared/matsim-metropole-ruhr/scenarios/metropole-ruhr-v2024.0/output/009_1pct/analysis/traffic/009.generalTravelData.csv",
                   delim = ";",
                   locale = locale(decimal_mark = "."))

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