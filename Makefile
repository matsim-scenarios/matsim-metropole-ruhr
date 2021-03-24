
JAR := matsim-metropole-ruhr-*.jar
V := v1.0

.PHONY: prepare

$(JAR):
	mvn package


scenarios/input/metropole-ruhr-$V-25pct.plans.xml.gz:
	java -jar $(JAR) prepare trajectory-to-plans\
	 --name metropole-ruhr-$V --sample-size 0.25\
	 --population ../../shared-svn/TODO/optimizedPopulation_filtered.xml.gz\
	 --attributes  ../../shared-svn/TODO/personAttributes.xml.gz


scenarios/input/metropole-ruhr-$V-25pct.plans-with-trips.xml.gz: scenarios/input/metropole-ruhr-$V-25pct.plans.xml.gz
	java -jar $(JAR) prepare generate-short-distance-trips\
	 --population $<\
	 --num-trips 10000


# TODO: number of trips!

# TODO: freight


# Aggregated target
prepare: scenarios/input/metropole-ruhr-$V-25pct.plans-with-trips.xml.gz
	echo "Done"