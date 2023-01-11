library(tidyverse)
library(readr)
library(sf)

SHP <- "C:/Users/ACER/Desktop/Uni/SoSe_21/MATSim/Bezirke_-_Berlin-shp/Berlin_Bezirke.shp"

BAST_STATIONS <- "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/BASt-Daten/Jawe2021.csv"
BAST_MOTORWAY <- "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/BASt-Daten/2021_A_S/2021_A_S.txt"
BAST_PRIMARY <- "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/2019_B_S/2019_B_S.txt"

stations <- read_csv2(BAST_STATIONS)

shp <- st_read(SHP, crs = 4326)

stations.1 <- stations %>%
  select(DZ_Nr, DZ_Name, Land_Code, Hi_Ri1, Hi_Ri2, Koor_UTM32_E, Koor_UTM32_N) %>%
  mutate(row =row_number(),
        # DZ_Nr = paste0("dummy_station_", row),
         DZ_Name = paste0("dummy_station_name", row)) %>%
#  st_as_sf(crs = 25832, coords = c("Koor_UTM32_E", "Koor_UTM32_N") ) %>%
#  st_filter(shp) %>%
  filter(Land_Code == "BE") %>%
#  mutate(geometry = 0) %>%
  as_tibble() %>%
  select(DZ_Nr) %>%
  left_join(stations, by = "DZ_Nr")

OUTPUT_DIR = "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/Testdaten/"
write_csv2(x = stations.1, file = paste0(OUTPUT_DIR, "Jawe2021_test_data.csv"))

motorway <- read_csv2(BAST_MOTORWAY)
motorway_test_data <- stations.1 %>%
  select(DZ_Nr) %>%
  left_join(motorway, by = c("DZ_Nr" = "Zst")) %>%
  filter(!is.na("Wotag")) %>%
  transmute(Zst = DZ_Nr, Wotag, Stunde, KFZ_R1, KFZ_R2, Lkw_R1, Lkw_R2) %>%
  mutate_at(c("KFZ_R1", "KFZ_R2", "Lkw_R1", "Lkw_R2"), function(x){
    x * runif(1, 0.9, 1.1)
  }) %>%
  filter(!is.na(Stunde))

con <- file(paste0(OUTPUT_DIR, "2021_A_S_test_data.txt"), encoding = "ISO-8859-1")

write.csv2(x = motorway_test_data, file = con, row.names = F, quote = F)

rm(motorway, motorway_test_data)

primary <- read_csv2(BAST_PRIMARY)
primary_test_data <- stations.1 %>%
  select(DZ_Nr) %>%
  left_join(primary, by = c("DZ_Nr" = "Zst")) %>%
  filter(!is.na("Wotag")) %>%
  transmute(Zst = DZ_Nr, Wotag, Stunde, KFZ_R1, KFZ_R2, Lkw_R1, Lkw_R2) %>%
  mutate_at(c("KFZ_R1", "KFZ_R2", "Lkw_R1", "Lkw_R2"), function(x){
    x * runif(1, 0.9, 1.1)
  }) %>%
  filter(!is.na(Stunde))

con <- file(paste0(OUTPUT_DIR, "2021_B_S_test_data.txt"), encoding = "ISO-8859-1")

write.csv2(x = primary_test_data, file = con, row.names = F, quote = F)

rm(primary, primary_test_data)


test_data_motorway <- read_csv2(file = "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/Testdaten/2021_A_S_test_data_old.txt")

test_data_motorway.1 <- test_data_motorway %>%
  group_by(Zst, Wotag, Stunde) %>%
  mutate(n = row_number())

max(test_data_motorway.1$n)

test_data_motorway.1 %>%
  filter(n < 10) %>%
  select(-n) %>%
  write_csv2(file = "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/Testdaten/2021_A_S_test_data.txt")
