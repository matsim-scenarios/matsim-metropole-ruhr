# The MATSim 'Metropole Ruhr' Scenario

<!--
![Ruhrgebiet MATSim network and agents](scenarios/visualization_network-agents.png "Metropole Ruhr MATSim network and agents")
-->

### About this project

This repository provides a MATSim transport model for the metropolitan area Ruhr (Metropole Ruhr) in Germany, developed by the [Transport Systems Planning and Transport Telematics group](https://www.vsp.tu-berlin.de) of [Technische Universität Berlin](http://www.tu-berlin.de) and the [Regionalverband Ruhr](https://www.rvr.ruhr).

<a rel="TU Berlin" href="https://www.vsp.tu-berlin.de"><img src="logos/TU_Logo.png" width="15%" height="15%"/></a>
<a rel="RVR" href="https://www.rvr.ruhr"><img src="logos/Logo_RVR_Pos_CMYK.png" width="30%" height="30%"/></a>

### Licenses

The **MATSim program code** in this repository is distributed under the terms of the [GNU General Public License as published by the Free Software Foundation (version 2)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html). The MATSim program code are files that reside in the `src` directory hierarchy and typically end with `*.java`.

The **MATSim input population** is licensed under the <a rel="license" href="https://creativecommons.org/licenses/by-nc/3.0/de/deed.de"> Creative Commons Attribution CC-BY-NC 3.0 DE License </a> and was provided in its initial version by Senozon Deutschland GmbH.
The **MATSim output files, analysis data and visualizations** are licensed under a <a rel="license" href="http://creativecommons.org/licenses/by-nc/4.0/">Creative Commons Attribution 4.0 International License</a>.
<a rel="license" href="http://creativecommons.org/licenses/by-nc/4.0/"><img alt="Creative Commons License" style="border-width:0" src="https://i.creativecommons.org/l/by-nc/4.0/80x15.png" /></a> MATSim input files are those that are used as input to run MATSim. They often, but not always, have a header pointing to matsim.org. They typically reside in the `scenarios` directory hierarchy. MATSim output files, analysis data, and visualizations are files generated by MATSim runs, or by postprocessing.  They typically reside in a directory hierarchy starting with `output`.

[//]: # (**Other data files**, in particular in `original-input-data`, have their own individual licenses that need to be individually clarified with the copyright holders.)

> [!NOTE]
> Handling of large files within git is not without problems (git lfs files are not included in the zip download; we have to pay; ...).  In consequence, large files, both on the input and on the output side, reside at https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/ .  

> [!NOTE]
> ~~We do not have licenses to make all input data publicly available. VSP members and RVR can find the non-public input files (e.g. the plans files) at https://svn.vsp.tu-berlin.de/repos/shared-svn/projects/matsim-metropole-ruhr/ .~~  (By now (Nov'23), this only concerns data that is not strictly necessary to make the model run, but we still need to finalize making the model available.)

> [!CAUTION]
> The following is not yet fully plugged together.  Things work in general _roughly_ as described, but the details are not yet consistent.  Sorry.

### Simple things (without installing/running MATSim)

<!--
##### Movies
1. Go to https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/.
1. Inside there, look for movie files.  You can't view them directly, but you there are various ways to download them, and you can view them then.  Try that.
-->

##### Run VIA on output files

1. Get VIA from https://www.simunto.com/via/.  (There is a free license for a small number of agents; that will probably work but only display a small number of vehicles/agents.)
1. Go to https://svn.vsp.tu-berlin.de/repos/public-svn/matsim/scenarios/countries/de/metropole-ruhr/.
1. Inside there, look for an `output-*` directory that you find interesting and go into that directory.
1. Download `*.output_network.xml.gz` and `*.output_events.xml.gz`.  Best make sure that they do not uncompress, e.g. by "Download linked file as ...".
1. Get these files into VIA.  This can be achieved in various ways; one is to open VIA and then drag the files from a file browser into VIA.
1. Run VIA and enjoy.

### Downloading the repository alternative 1: Download ZIP

1. Click on `Clone or download` and then on `Download ZIP`.
1. Unzip the repository.
1. Go to "Run the scenario" below.

### Downloading the repository alternative 2: Clone the repository

##### Initial stuff (needs to be done once)

1. Install git for the command line.
1. Type `git clone https://github.com/matsim-vsp/matsim-metrople-ruhr.git` in the command line.

(Or use your IDE, e.g. Eclipse, IntelliJ, to clone the repository.)

This will result in a new `matsim-metrople-ruhr` directory.  Memorize where you have put it.  You can move it, as a whole, to some other place.

##### Update your local clone of the repository.

1. Go into the `matsim-metrople-ruhr` directory.
1. Type `git pull`

(Or use your IDE, e.g. Eclipse, IntelliJ, to update the repository.)

This will update your repository to the newest version.

### Run the scenario
(Requires either cloning or downloading the repository.)

There are two config files for a `10pct` and `3pct` scenario. The default ist `3pct`, the other config is not in a fully relaxed calibrated state.

##### ... using a runnable jar file (only available for releases)
1. You can build an executable jar-file by executing one of the following commands in the top directory. 
This will download all necessary dependencies (it might take a while the first time it is run) and dump the jar into the top directory.
   1. `./mvnw clean package -DskipTests=true`    
   1. or on Windows: `mvnw.cmd clean package -DskipTests=true`
1. Double-click on that .jar file (in a file system browser). Alternatively, try opening it with the following command:``
java -jar [FILENAME].jar
``

1. In the GUI, click on the "Choose" button for configuration file.  Navigate to one of the `scenario` directories and load one of the configuration files.
1. Increase memory in the GUI.
1. Press the "Start MATSim" button.  This should run MATSim.  Note that MATSim accepts URLs as filenames in its config, so while the config files are part of the git repo, running them will pull additional material from our server.
1. "Open" the output directory.  You can drag files into VIA as was already done above.
1. "Edit..." (in the GUI) the config file.  Re-run MATSim.

##### ... using an IDE, e.g. Eclipse, IntelliJ
1. Set up the project in your IDE.
1. Make sure the project is configured as maven project.
1. Run the JAVA class `src/main/java/org/matsim/run/RunMetropoleRuhrScenario.java` or `src/main/java/org/matsim/gui/MATSimGUI.java`.
1. "Open" the output directory.  You can drag files into VIA as was already done above.
1. Edit the config file or adjust the run class. Re-run MATSim.

### More information

For more information about MATSim, see here: https://www.matsim.org/.

For more information about traffic simulation and traffic data at the Regionalverband Ruhr, see here: https://www.rvr.ruhr/themen/mobilitaet/informationssystem-ruhrmobil/

### Acknowledgements

Some functionality in this repository is based on the 'MATSim Open Ruhrgebiet' model (https://github.com/matsim-scenarios/matsim-ruhrgebiet) where the development was made possible by Stiftung Mercator (https://www.stiftung-mercator.de/) within the project Neue Emscher Mobilität (NEMO, https://www.nemo-ruhr.de).

The travel demand data for the metropolitan area Ruhr is provided by Senozon Deutschland GmbH (https://senozon.com).

### Internal documentation

Internal documentation can be found here:
https://docs.google.com/document/d/1GQhdJh4Cg4ZrzbcP_aB4KYK6jxIGmj5EB1eyIbWNbAU/edit?usp=sharing
