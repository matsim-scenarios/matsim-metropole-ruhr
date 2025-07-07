#!/usr/bin/env python
# -*- coding: utf-8 -*-
import sys
import geopandas as gpd

from matsim.calibration import create_calibration, ASCCalibrator, utils

# %%

modes = ["walk", "car", "ride", "pt", "bike"]
fixed_mode = "walk"

# Initial ASCs
initial = {
    "bike": 0.33,
    "pt": -0.38,
    "car": 0.56,
    "ride": 0.27
}

# Modal split target
target = {
    "walk": 0.232118,
    "bike": 0.098503,
    "pt": 0.116146,
    "car": 0.424297,
    "ride": 0.128936
}

input_path = "../../scenarios/metropole-ruhr-v2.0/input"

region = gpd.read_file(input_path + "/pt-pricing/pt_preisstufen_fare_all3.0.shp").set_crs("EPSG:25832")


def f(persons):
    persons = gpd.GeoDataFrame(persons, geometry=gpd.points_from_xy(persons.home_x, persons.home_y))

    df = gpd.sjoin(persons.set_crs("EPSG:25832"), region, how="inner", predicate="intersects")
    return df


def adjust_trips(df):
    df = df[df.main_mode != "freight"]

    # Assign all intermodal pt trips to pt as main mode
    df.loc[df.main_mode.str.startswith("pt_"), "main_mode"] = "pt"

    return df

# Use addtional arguments to pass to matsim
addtional_arguments = sys.argv[1] if len(sys.argv) > 1 else ""

study, obj = create_calibration(
    "calib",
    ASCCalibrator(modes, initial, target, lr=utils.linear_scheduler(start=0.3, interval=8)),
    "matsim-metropole-ruhr-2.0-2e225bc.jar",
    input_path + "/metropole-ruhr-v2.0-3pct.config.xml",
    args="--10pct --no-intermodal " + addtional_arguments,
    jvm_args="-Xmx80G -Xms80G -XX:+AlwaysPreTouch -XX:+UseParallelGC",
    transform_persons=f, transform_trips=adjust_trips,
    chain_runs=utils.default_chain_scheduler, debug=False
)

# %%

study.optimize(obj, 1)
