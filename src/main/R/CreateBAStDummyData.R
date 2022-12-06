library(tidyverse)
library(readr)

randomizeBAStData <- function(filepath, stations){
  
  data = read_csv2(filepath)
  
  data.1 <- stations %>%
    select(DZ_Nr) %>%
    left_join(data, by = c("DZ_Nr" = "Zst")) %>%
    filter(!is.na("Wotag")) %>%
    transmute(Zst = DZ_Nr, Wotag, Stunde, KFZ_R1, KFZ_R2, Lkw_R1, Lkw_R2) %>%
    mutate_at(c("KFZ_R1", "KFZ_R2", "Lkw_R1", "Lkw_R2"), function(x){
      x * runif(1, 0.9, 1.1)
    })
  
  data.1
}

BAST_STATIONS <- "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/BASt-Daten/Jawe2021.csv"
BAST_MOTORWAY <- "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/BASt-Daten/2021_A_S/2021_A_S.txt"
BAST_PRIMARY <- "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/2019_B_S/2019_B_S.txt"

stations <- read_csv2(BAST_STATIONS)
codes <- c("NW", "SL", "SN")

stations.1 <- stations %>%
  select(DZ_Nr, DZ_Name, Land_Code, Hi_Ri1, Hi_Ri2, Koor_UTM32_E, Koor_UTM32_N) %>%
  mutate(row =row_number(),
         DZ_Nr = paste0("dummy_station_", row),
         DZ_Name = paste0("dummy_station_name", row)) %>%
  filter(Land_Code %in% codes)

stations.2 <- stations %>%
  filter(Land_Code %in% codes)

OUTPUT_DIR = "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/Testdaten/"
write_csv2(x = stations.1, file = paste0(OUTPUT_DIR, "Jawe2021_test_data.csv"))

motorway <- read_csv2(BAST_MOTORWAY)
motorway_test_data <- stations.2 %>%
  select(DZ_Nr) %>%
  left_join(motorway, by = c("DZ_Nr" = "Zst")) %>%
  filter(!is.na("Wotag")) %>%
  transmute(Zst = DZ_Nr, Wotag, Stunde, KFZ_R1, KFZ_R2, Lkw_R1, Lkw_R2) %>%
  mutate_at(c("KFZ_R1", "KFZ_R2", "Lkw_R1", "Lkw_R2"), function(x){
    x * runif(1, 0.9, 1.1)
  })
write_csv2(x = motorway_test_data, file = paste0(OUTPUT_DIR, "2021_A_S_test_data.csv"))

rm(motorway, motorway_test_data)

primary <- read_csv2(BAST_PRIMARY)
primary_test_data <- stations.2 %>%
  select(DZ_Nr) %>%
  left_join(primary, by = c("DZ_Nr" = "Zst")) %>%
  filter(!is.na("Wotag")) %>%
  transmute(Zst = DZ_Nr, Wotag, Stunde, KFZ_R1, KFZ_R2, Lkw_R1, Lkw_R2) %>%
  mutate_at(c("KFZ_R1", "KFZ_R2", "Lkw_R1", "Lkw_R2"), function(x){
    x * runif(1, 0.9, 1.1)
  })
write_csv2(x = primary_test_data, file = paste0(OUTPUT_DIR, "2021_B_S_test_data.csv"))

rm(primary, primary_test_data)
