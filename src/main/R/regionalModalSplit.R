library(tidyverse)
library(dplyr)
library(patchwork)
library(networkD3)
library(sf) #=> geography
library(matsim)
library(stringr)
library("xlsx")

completeTripsTable <-readTripsTable("/Users/gregorr/Documents/work/respos/runs-svn/rvr-ruhrgebiet/v1.2.1/036/036.output_trips.csv.gz")
person2home <- read_csv2("/Users/gregorr/Documents/work/respos/runs-svn/rvr-ruhrgebiet/v1.2.1/036/person2Home.csv")
completeTripsTable <- left_join(completeTripsTable, person2home, by = "person")

##calculating regional ModalSplits
regionalModalSplits <- completeTripsTable %>% group_by(area) %>% count(main_mode) %>%
  mutate(n = n / sum(n) * 100)
regionalModalSplits <- pivot_wider(regionalModalSplits, names_from = main_mode, values_from = n)
regionalModalSplits <-mutate(regionalModalSplits, car = car + ride, ride=NULL)

## rvr Data
rvrModalSplitData <- read_csv2("/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/data/regionaleModalSplits/regionalModalSplitsRVR.csv")
modalSplitsOfModelAndRVR <- left_join(regionalModalSplits, rvrModalSplitData, by = "area")

write_excel_csv2(regionalModalSplits, file = "/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/data/regionaleModalSplits/regionalModalSplitsModelResults.csv")

########plotting
##### https://stackoverflow.com/questions/57107721/how-to-name-the-list-of-the-group-split-output-in-dplyr
completeTripsTable <- completeTripsTable %>% group_by(area)
completeTripsTableList <- group_split(completeTripsTable) %>%  setNames(unique(completeTripsTable$area))

myplots <- list() 

for (ii in 1:length(completeTripsTableList)) {
  df <-completeTripsTableList[[ii]]
  areaName <- df %>% select(area) %>% distinct()
  plot <- plotModalSplitBarChart(df) + ggtitle(areaName)
  myplots[[ii]] <- plot  # add each plot into plot list
}

for(i in 1:length(myplots)) {
  png(paste("/Users/gregorr/Desktop/Plots/ModalSplit", i, ".png"), res=200)
  set.seed(i)
  plot(myplots[[i]]) 
  dev.off()
}
