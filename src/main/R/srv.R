

library(gridExtra)
library(tidyverse)
library(lubridate)
library(viridis)
library(ggsci)
library(sf)

source("https://raw.githubusercontent.com/matsim-scenarios/matsim-duesseldorf/master/src/main/R/theme.R")

# setwd("C:/Users/chris/Development/matsim-scenarios/matsim-metropole-ruhr/src/main/R")

# trip distance groups
levels = c("0 - 1000", "1000 - 2000", "2000 - 5000", "5000 - 10000", "10000 - 20000", "20000+")
breaks = c(0, 1000, 2000, 5000, 10000, 20000, Inf)

shape <- st_read("../../../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp", crs=25832)

# Total trips per day
tt <- 17728996

##################
# Read survey data
##################

persons <- read_delim("../../../../shared-svn/projects/rvr-metropole-ruhr/data/MID/MiD2017_Personen_RVR-Gebiet.csv", delim = ";", 
                      locale = locale(decimal_mark = ",")) %>%
  filter(kernwo == 2) %>%
  filter(anzwege3 >= 0 & anzwege3 < 100)

# Avg. number of trips per person per day
per_day <- weighted.mean(persons$anzwege3, persons$gew_pers)

# number of total trips, based on residents
tt <- per_day * 5112050

#######

trips <- read_delim("../../../../shared-svn/projects/rvr-metropole-ruhr/data/MID/MiD2017_Wege_RVR-Gebiet.csv", delim = ";", 
                    col_types = cols(
                    ), locale = locale(decimal_mark = ","))


# categories as defined in SrV mapped to matsim mode
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


write_csv(srv, "mid.csv")

#############

srv <- read_csv("mid_adj.csv") %>%
    mutate(main_mode=mode) %>%
    mutate(scaled_trips=tt * share) %>%
    mutate(source = "srv") %>%
    mutate(dist_group=fct_relevel(dist_group, levels)) %>%
    arrange(dist_group)


##################
# Read simulation data
##################

f <- "\\\\sshfs.kr\\rakow@cluster.math.tu-berlin.de\\net\\ils4\\matsim-metropole-ruhr\\calibration\\output\\base-10pct"
sim_scale <- 10

persons <- read_delim(list.files(f, pattern = "*.output_persons.csv.gz", full.names = T, include.dirs = F), delim = ";", trim_ws = T, 
                     col_types = cols(
                       person = col_character()
                     )) %>%
          st_as_sf(coords = c("first_act_x", "first_act_y"), crs = 25832) %>%
          st_filter(shape)

trips <- read_delim(list.files(f, pattern = "*.output_trips.csv.gz", full.names = T, include.dirs = F), delim = ";", trim_ws = T, 
                    col_types = cols(
                      person = col_character()
                    )) %>%
        filter(main_mode!="freight") %>%
        semi_join(persons) %>%
        mutate(dist_group = cut(traveled_distance, breaks=breaks, labels=levels)) %>%
        filter(!is.na(dist_group))


sim <- trips %>%
  group_by(dist_group, main_mode) %>%
  summarise(trips=n()) %>%
  mutate(mode = fct_relevel(main_mode, "walk", "bike", "pt", "ride", "car")) %>%
  mutate(scaled_trips=sim_scale * trips) %>%
  mutate(source = "sim")

write_csv(sim, "sim.csv")

##################
# Total modal split
##################

srv_aggr <- srv %>%
    group_by(mode) %>%
    summarise(share=sum(share)) %>%  # assume shares sum to 1
    mutate(mode=fct_relevel(mode, "walk", "bike", "pt", "ride", "car"))  
  
aggr <- sim %>%
    group_by(mode) %>%
    summarise(share=sum(trips) / sum(sim$trips))

p1_aggr <- ggplot(data=srv_aggr, mapping =  aes(x=1, y=share, fill=mode)) +
  labs(subtitle = "Survey data") +
  geom_bar(position="fill", stat="identity") +
  coord_flip() +
  geom_text(aes(label=scales::percent(share, accuracy = 0.1)), size= 5, position=position_fill(vjust=0.5)) +
  scale_fill_locuszoom() +
  theme_void() +
  theme(legend.position="none")

p2_aggr <- ggplot(data=aggr, mapping =  aes(x=1, y=share, fill=mode)) +
  labs(subtitle = "Simulation") +
  geom_bar(position="fill", stat="identity") +
  coord_flip() +
  geom_text(aes(label=scales::percent(share, accuracy = 0.1)), size= 5, position=position_fill(vjust=0.5)) +
  scale_fill_locuszoom() +
  theme_void()

g <- arrangeGrob(p1_aggr, p2_aggr, ncol = 2)
ggsave(filename = "modal-split.png", path = ".", g,
       width = 12, height = 2, device='png', dpi=300)

#########
# Combined plot by distance
##########

total <- bind_rows(srv, sim)

# Maps left overgroups
dist_order <- factor(total$dist_group, level = levels)
dist_order <- fct_explicit_na(dist_order, "20000+")

ggplot(total, aes(fill=mode, y=scaled_trips, x=source)) +
  labs(subtitle = paste("Metropole Ruhr scenario", f), x="distance [m]") +
  geom_bar(position="stack", stat="identity", width = 0.5) +
  facet_wrap(dist_order, nrow = 1)


# Needed for adding short distance trips

sim_sum <- sum(sim$trips)

# Needed share of trips
tripShare <- sum(filter(srv, dist_group=="0 - 1000")$share)
shortDistance <- sum(filter(sim, dist_group=="0 - 1000")$trips)
numTrips = (shortDistance - sim_sum * tripShare) / (tripShare - 1)
