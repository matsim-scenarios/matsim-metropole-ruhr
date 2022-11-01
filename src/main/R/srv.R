

library(gridExtra)
library(tidyverse)
library(lubridate)
library(patchwork)
library(scales)
library(viridis)
library(ggsci)
library(sf)

source("https://raw.githubusercontent.com/matsim-scenarios/matsim-duesseldorf/master/src/main/R/theme.R")

setwd("C:/Users/chris/Development/matsim-scenarios/matsim-metropole-ruhr/src/main/R")

# trip distance groups
levels = c("0 - 1000", "1000 - 2000", "2000 - 5000", "5000 - 10000", "10000 - 20000", "20000+")
breaks = c(0, 1000, 2000, 5000, 10000, 20000, Inf)

shape <- st_read("../../../../shared-svn/projects/rvr-metropole-ruhr/matsim-input-files/20210520_regionalverband_ruhr/dilutionArea.shp", crs=25832)

# Alternative shape-file for hamm
shape <- st_read("../../../../shared-svn/projects/matsim-metropole-ruhr/hamm-v1.0/original-data/Stadtgebiet_Hamm/Stadtgebiet_Hamm.shp")

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

lookup_im <- tibble(category = c(1, 2, 3, 4, 5, 6, 7, 8, 9), 
                 mode_combi = c("walk", "bike", "pt_w_bike_used", "pt", "pt_w_ride_used", "pt_w_car_used", "ride", "car", "pt_w_unknown"))

order_modes <- c("walk", "bike", "pt","ride","car")
order_modes_im <- c("walk", "bike", "pt", "ride", "car", "pt_w_bike_used", "pt_w_unknown", "pt_w_bike_and_car_used", "pt_w_ride_used", "pt_w_car_used")

# Filter invalid modes and trip distances, also filter for weekdays
relevant <- trips %>%
  filter(kernwo == 2) %>%
  filter(wegkm < 1000) %>%
  filter(hvm < 9) %>%
  mutate(dist=wegkm * 1000) %>%
  mutate(dist_group = cut(dist, breaks=breaks, labels=levels))

matched <- relevant %>% 
  left_join(lookup, by=c("hvm"="category")) %>%
  left_join(lookup_im, by=c("vm_kombi"="category"))

srv <- matched %>%
  group_by(dist_group, mode) %>%
  summarise(trips=sum(gew_wege)) %>%
  mutate(mode = fct_relevel(mode, order_modes)) %>%
  mutate(source = "srv")

srv <- srv %>%
  mutate(share=trips / sum(srv$trips)) %>%
  mutate(scaled_trips=tt * share)


srv_mode_user <- matched %>%
    left_join(persons, by = "HP_ID") %>%
    group_by(HP_ID, mode) %>%
    summarize(count=n(), gew=first(gew_pers))

weight <- srv_mode_user %>%
          group_by(HP_ID) %>%
          summarize(weight=first(gew))

# Weight makes huge difference

srv_mode_user <- srv_mode_user %>%
    group_by(mode) %>%
    summarize(share=sum(gew)/ sum(weight$weight))
#    summarize(share=n()/n_distinct(srv_mode_user$HP_ID))

#-----

srv_im <- matched %>%
  group_by(dist_group, mode_combi) %>%
  summarise(trips=sum(gew_wege)) %>%
  mutate(mode_combi = fct_relevel(mode_combi, order_modes_im)) %>%
  rename(mode = mode_combi) %>%
  mutate(source = "srv")

srv_im <- srv_im %>%
  mutate(share=trips / sum(srv_im$trips)) %>%
  mutate(scaled_trips=tt * share)


write_csv(srv, "mid.csv")
write_csv(srv_im, "mid_im.csv")

#############

srv <- read_csv("mid_adj.csv") %>%
    mutate(main_mode=mode) %>%
    mutate(scaled_trips=tt * share) %>%
    mutate(source = "srv") %>%
    mutate(dist_group=fct_relevel(dist_group, levels)) %>%
    arrange(dist_group)

srv_im <- read_csv("mid_im_adj.csv") %>%
  mutate(main_mode=mode) %>%
  mutate(scaled_trips=tt * share) %>%
  mutate(source = "srv") %>%
  mutate(dist_group=fct_relevel(dist_group, levels)) %>%
  arrange(dist_group)


srv_hamm <- tibble(
  mode=c("walk", "bike", "pt", "car", "ride"),
  share=c(0.17, 0.19, 0.08, 0.46, 0.10)
) %>% mutate(mode=fct_relevel(mode, order_modes))

##################
# Read simulation data
##################

f <- "\\\\sshfs.kr\\rakow@cluster.math.tu-berlin.de\\net\\ils\\matsim-metropole-ruhr\\calibration-1.4-3pct\\runs\\010"

# Hamm
f <- "\\\\sshfs.kr\\rakow@cluster.math.tu-berlin.de\\net\\ils\\matsim-metropole-ruhr\\hamm\\calibration\\runs\\007"

sim_scale <- 100/3

homes <- read_csv("../../../../shared-svn/projects/matsim-metropole-ruhr/metropole-ruhr-v1.0/input/metropole-ruhr-v1.4-25pct.plans-homes.csv", 
                  col_types = cols(
                    person = col_character()
                  ))


persons <- read_delim(list.files(f, pattern = "*.output_persons.csv.gz", full.names = T, include.dirs = F), delim = ";", trim_ws = T, 
                     col_types = cols(
                       person = col_character()
                     )) %>%
            right_join(homes) %>%
            st_as_sf(coords = c("home_x", "home_y"), crs = 25832) %>%
  #        st_as_sf(coords = c("first_act_x", "first_act_y"), crs = 25832) %>%
          st_filter(shape)



trips <- read_delim(list.files(f, pattern = "*.output_trips.csv.gz", full.names = T, include.dirs = F), delim = ";", trim_ws = T, 
                    col_types = cols(
                      person = col_character()
                    )) %>%
        filter(main_mode!="freight") %>%
        mutate(mode_combi=main_mode) %>%
        mutate(main_mode=ifelse(startsWith(main_mode, "pt_"), "pt", main_mode)) %>%
        semi_join(persons) %>%
        mutate(dist_group = cut(traveled_distance, breaks=breaks, labels=levels, right = F)) %>%  # traveled_distance == 0 is considered
        filter(!is.na(dist_group))


sim <- trips %>%
  group_by(dist_group, main_mode) %>%
  summarise(trips=n()) %>%
  mutate(mode = fct_relevel(main_mode, order_modes)) %>%
  mutate(scaled_trips=sim_scale * trips) %>%
  mutate(source = "sim")

sim_im <- trips %>%
  group_by(dist_group, mode_combi) %>%
  summarise(trips=n()) %>%
  mutate(mode_combi = fct_relevel(mode_combi, order_modes_im)) %>%
  rename(mode = mode_combi) %>%
  mutate(scaled_trips=sim_scale * trips) %>%
  mutate(source = "sim")


sim_mode_user <- trips %>%
  group_by(person, main_mode) %>%
  summarize(count=n())

sim_mode_user <- sim_mode_user %>%
  group_by(main_mode) %>%
  summarize(share=n()/n_distinct(sim_mode_user$person))

#write_csv(sim, "sim.csv")

##################
# Total modal split
##################

srv_aggr <- srv %>%
    group_by(mode) %>%
    summarise(share=sum(share)) %>%  # assume shares sum to 1
    mutate(mode=fct_relevel(mode, order_modes))
  
aggr <- sim %>%
    group_by(mode) %>%
    summarise(share=sum(trips) / sum(sim$trips)) %>%
    mutate(mode=fct_relevel(mode, order_modes))

p1_aggr <- ggplot(data=srv_aggr, mapping =  aes(x=1, y=share, fill=mode)) +
  labs(subtitle = "Survey data") +
  geom_bar(position="fill", stat="identity") +
  coord_flip() +
  geom_text(aes(label=scales::percent(share, accuracy = 0.1)), angle=90, size=6, position=position_fill(vjust=0.5)) +
  scale_fill_locuszoom() +
  theme_void() +
  theme(legend.position="none")

p2_aggr <- ggplot(data=aggr, mapping =  aes(x=1, y=share, fill=mode)) +
  labs(subtitle = "Simulation") +
  geom_bar(position="fill", stat="identity") +
  coord_flip() +
  geom_text(aes(label=scales::percent(share, accuracy = 0.1)), angle=90, size=6, position=position_fill(vjust=0.5)) +
  scale_fill_locuszoom() +
  theme_void() +
  theme(legend.position = "bottom")

combined <- p1_aggr / p2_aggr
combined + plot_layout(guides = "auto")

#########
# Combined plot by distance
##########

total <- bind_rows(srv, sim) %>%
        mutate(mode=fct_relevel(mode, order_modes))

# Filter srv trips
#total <- total %>%
#    mutate(scaled_trips=ifelse(source=="srv", yes=0, no=scaled_trips))

# Maps left overgroups
dist_order <- factor(total$dist_group, level = levels)
dist_order <- fct_explicit_na(dist_order, "20000+")

ggplot(total, aes(fill=mode, y=scaled_trips, x=source)) +
  labs(subtitle = paste("Metropole Ruhr scenario", substring(f, 52)), x="distance [m]", y="trips") +
  geom_bar(position="stack", stat="identity", width = 0.5) +
  facet_wrap(dist_order, nrow = 1) +
  scale_y_continuous(labels = scales::number_format(suffix = " M", scale = 1e-6)) +
  scale_fill_locuszoom() +
  theme_minimal()

#########
# Intermodal plots
##########

srv_aggr_im <- srv_im %>%
  group_by(mode) %>%
  summarise(share=sum(share)) %>%  # assume shares sum to 1
  filter(grepl("pt", mode)) %>%
  mutate(mode=fct_relevel(mode, order_modes_im))

aggr_im <- sim_im %>%
  group_by(mode) %>%
  summarise(share=sum(trips) / sum(sim$trips)) %>%
  filter(grepl("pt", mode)) %>%
  mutate(mode=fct_relevel(mode, order_modes_im))

p1_aggr_im <- ggplot(data=srv_aggr_im, mapping =  aes(x=1, y=share, fill=mode)) +
  labs(subtitle = "Survey data") +
  geom_bar(position="fill", stat="identity") +
  coord_flip() +
  geom_text(aes(label=scales::percent(share, accuracy = 0.01)), size= 5, position=position_fill(vjust=0.5), angle=90) +
  scale_fill_manual(values=c("pt"="blue", "pt_w_bike_used"="orange", "pt_w_car_used"="lightblue", "pt_w_ride_used"="green", "pt_w_unknown"="grey")) +
  theme_void() +
  theme(legend.position="bottom")

p2_aggr_im <- ggplot(data=aggr_im, mapping =  aes(x=1, y=share, fill=mode)) +
  labs(subtitle = "Simulation") +
  geom_bar(position="fill", stat="identity", color=c("darkblue", "orange", "lightblue", "turquoise")) +
  coord_flip() +
  geom_text(aes(label=scales::percent(share, accuracy = 0.01)), size= 5, position=position_fill(vjust=0.5), angle=90) +
  scale_fill_locuszoom() +
  theme_void() +
  theme(legend.position = "bottom")

# TODO: colors are not consistent

combined_im <- p1_aggr_im / p2_aggr_im / guide_area()
combined_im + plot_layout(guides = "collect")

# intermodal distance plot
# -------------------

total_im <- bind_rows(srv_im, sim_im) %>%
  mutate(mode=fct_relevel(mode, order_modes_im)) %>%
  filter(str_starts(mode, "pt_"))

# Maps left overgroups
dist_order_im <- factor(total_im$dist_group, level = levels)
dist_order_im <- fct_explicit_na(dist_order_im, "20000+")

ggplot(total_im, aes(fill=mode, y=scaled_trips, x=source)) +
  labs(subtitle = paste("Metropole Ruhr scenario", substring(f, 52)), x="distance [m]", y="intermodal trips") +
  geom_bar(position="stack", stat="identity", width = 0.5) +
  facet_wrap(dist_order_im, nrow = 1) +
  scale_y_continuous(labels = scales::number_format(suffix = " K", scale = 1e-3)) +
  scale_fill_uchicago() +
  theme_minimal()


#########
# Calculation for short term trips
##########

sim_sum <- sum(sim$trips)

# Needed share of trips
tripShare <- sum(filter(srv, dist_group=="0 - 1000")$share)
shortDistance <- sum(filter(sim, dist_group=="0 - 1000")$trips)
numTrips = (shortDistance - sim_sum * tripShare) / (tripShare - 1)
