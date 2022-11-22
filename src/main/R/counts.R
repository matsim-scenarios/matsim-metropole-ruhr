devtools::install_github("matsim-vsp/matsim-r",ref="counts")
library(tidyverse)
library(matsim)

FILE_DIR <-  "C:/Users/ACER/Desktop/Uni/VSP/Ruhrgebiet/"
LINKSTATS <- paste0(FILE_DIR, "linkstats.tsv.gz")
COUNTS <- paste0(FILE_DIR, "counts-from-bast.xml.gz")


# NOTE:
# read_xml may not work on big xml data sets while data is zipped
# unzip the file and run code again
#
#NETWORK <- paste0(FILE_DIR, "009.output_network_reduced.xml.gz")
#network <- loadNetwork(filename = NETWORK)

NETWORK_CSV <- "network_links.csv" 
network <- list(links = read.csv(NETWORK_CSV))

linkstats <- readLinkStats(runId = "metropole-ruhr-v1.4", file = LINKSTATS, sampleSize = 0.03)

linkStats <- list(linkstats)

counts <- readCounts(file = COUNTS)

join <- mergeCountsAndLinks(counts = counts, network = network, linkStats = linkStats, aggr_to = "day")

#####
createCountScatterPlot(joinedFrame = join, ll = ~x * 0.8 - 200) + coord_cartesian(ylim = c(1000, 100000), xlim = c(3000, 60000))
ggsave(filename = paste0(FILE_DIR, "scatterplot_zoomed.png"), device = "png")

dtv.quality <- processDtvEstimationQuality(joinedFrame = join) %>%
  filter(!is.na(type))

ggplot(dtv.quality, aes(x = estimation, y = share, fill = type)) +
  
  geom_col() +
  
  facet_grid(. ~ type) +
  
  labs(x = "Estimation Quality", y = "Share", fill = "Road type") +
  
  theme_bw() +
  
  theme(legend.position = "bottom")

ggsave(filename = paste0(FILE_DIR, "dtv_estimation_quality.png"), device = "png")

rm(dtv.quality)

dtv.distribution <- processLinkStatsDtvDistribution(joinedFrame = join, to = 60000)

ggplot(dtv.distribution, aes(x = traffic_bin, y = share, fill = type)) +
  
  geom_col() +
  
  facet_grid(src ~ type) +
  
  labs(x = "Daily traffic volume", y= "Share", fill = "Road type") +
  
  theme_bw() +
  
  theme(legend.position = "bottom", axis.text.x = element_text(angle = 90))

ggsave(filename = paste0(FILE_DIR, "dtv_distribution.png"), device = "png")

rm(dtv.distribution)
