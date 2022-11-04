library(tidyverse)
library(dplyr)
library(patchwork)
library(scales)
library(gridExtra)
library(lubridate)
library(scales)
library(viridis)
library(ggsci)
library(sf)


### trips.csv to get nrOfTrips
completeTrips <- read_csv2("output_trips.csv.gz")
## persons.csv to get SNZ attributes
persons <- read_csv2("output_persons.csv.gz")

## person2homeRegionAndCoord
person2region <-read_csv2("person2Home.csv")
## joining person and persons2region
persons <- left_join(persons, person2region, by= "person")

###these are only the agents with nrOfTrips >1
nrOfTripsPerMobilePerson <- completeTrips %>% group_by(person) %>% count()
nrOfTripsPerMobilePerson <- rename(nrOfTripsPerMobilePerson, nrOfTrips=n)
averageNrOfTripsMobileAgents <- mean(nrOfTripsPerMobilePerson$nrOfTrips)

##these also includes agents who have zero trips
nrOfTripsEveryPerson <- left_join(persons, nrOfTripsPerMobilePerson, by="person")
nrOfTripsEveryPerson <- nrOfTripsEveryPerson %>% replace(is.na(.), 0)
averageNrOfTripsEveryAgents <- round(mean(nrOfTripsEveryPerson$nrOfTrips),2)

##trip number for the diffrent regions
averageNrOfTripsEveryAgentByRegion <- nrOfTripsEveryPerson %>%
  group_by(area) %>%
  summarise_at(vars(nrOfTrips), list(nrOfTrips = mean))

averageNrOfTripsEveryAgentByRegion <- averageNrOfTripsEveryAgentByRegion %>% mutate(nrOfTrips= round(nrOfTrips,2))
##plotting results

#grouped absolute plot
ggplot(averageNrOfTripsEveryAgentByRegion, aes(y=area, x=nrOfTrips)) + 
  geom_bar(position="stack", stat="identity") +
  xlab("durchschnittliche Anzahl an Wegen") +
  ylab("Landkreis") +
  ggtitle("Resultate des Modells")+
  geom_text(aes(label = nrOfTrips), position = position_stack(vjust = 0.5)) +
  theme_minimal()

######################
#MiD Data
#####################


# trip distance groups
levels = c("0 - 1000", "1000 - 2000", "2000 - 5000", "5000 - 10000", "10000 - 20000", "20000+")
breaks = c(0, 1000, 2000, 5000, 10000, 20000, Inf)

shape <- st_read("/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp", crs=25832)

# Total trips per day
tt <- 17728996

##################
# Read survey data
##################

persons <- read_delim("/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/data/MID/MiD2017_Personen_RVR-Gebiet.csv", delim = ";", 
                      locale = locale(decimal_mark = ",")) %>%
  filter(kernwo == 2) %>%
  filter(anzwege3 >= 0 & anzwege3 < 100)

# Avg. number of trips per person per day
per_day <- weighted.mean(persons$anzwege3, persons$gew_pers)

# number of total trips, based on residents
tt <- per_day * 5112050

#######

trips <- read_delim("/Users/gregorr/Documents/work/respos/shared-svn/projects/rvr-metropole-ruhr/data/MID/MiD2017_Wege_RVR-Gebiet.csv", delim = ";", 
                    col_types = cols(
                    ), locale = locale(decimal_mark = ","))


# categories as defined in MiD mapped to matsim mode
lookup <- tibble(category = c(1, 2, 3, 4, 5), 
                 mode = c("walk", "bike", "ride", "car", "pt"))

# Filter invalid modes and trip distances, also filter for weekdays
relevant <- trips %>%
  filter(kernwo == 2) %>%
  filter(wegkm < 1000) %>%
  filter(hvm < 9) %>%
  mutate(dist=wegkm * 1000) %>%
  mutate(dist_group = cut(dist, breaks=breaks, labels=levels))

matched <- relevant %>% left_join(lookup, by=c("hvm"="category"))

srv <- matched %>%
  group_by(dist_group, mode) %>%
  summarise(trips=sum(gew_wege)) %>%
  mutate(mode = fct_relevel(mode, "walk", "bike", "pt", "ride", "car")) %>%
  mutate(source = "srv")


srv <- srv %>%
  mutate(share=trips / sum(srv$trips)) %>%
  mutate(scaled_trips=tt * share)


###create data frame for plot
combinedAverageTripNumbers <- data.frame(
  name = c("averageNrOfTripsEveryAgents","averageNrOfTripsMobileAgents","averageNrOfTripsPerMiD"),
  values = c(averageNrOfTripsEveryAgents,averageNrOfTripsMobileAgents,per_day), 
 stringsAsFactors = FALSE)

#grouped absolute plot
ggplot(combinedAverageTripNumbers, aes(y=name, x=values)) + 
  geom_bar(position="stack", stat="identity") +
  xlab("durchschnittliche Anzahl an Wegen") +
  ggtitle("durchschnittliche Anzahl an Wegen")+
  geom_text(aes(label = values), position = position_stack(vjust = 0.5)) +
  theme_minimal()
  