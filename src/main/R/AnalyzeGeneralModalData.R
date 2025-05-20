library(readr)
library(dplyr)
library(ggplot2)

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
  labs(title = "Anzahl Agenten je Subpopulation",
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


