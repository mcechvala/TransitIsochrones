/*
 * Author: Mike Cechvala
 * This app calculates the travel times from a single point to all bus stops 
 * which can then be used to create isochrone maps
 */
package isochrones;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
//import java.util.Date;

public class Isochrones {

    //Dimensions are generally radians, feet, and minutes
    //All these arrays will be redimmed later and made bigger
    public static int[] stopsDist = new int[1]; //Walking time to stop
    public static double[] stopsLat = new double[1], stopsLon = new double[1];
    public static int[] stopsArrival = new int[1]; //Earliest possible transit arrival at each stop
    public static int[] stopsDeparture = new int[1]; //Departure stop to get to each arrival stop
    public static String[] routeTaken = new String[1];
    public static String[] transferRouteTaken = new String[1];
    public static int[] departureStopID = new int[1];
    //stopsArrival, stopsDeparture, routeTaken, transferRouteTaken, and departureStopID are redimensioned in loadStopsDataToMemory
    public static double walkSpeed = 3.0 * 60; // fpm, takes into account out of direction travel
    public static int maxWalk = 1320; // feet
    public static int startTime;
    public static int endTime;
    public static int tripIDCol = -1, departureTimeCol = -1, routeSNCol = -1, puTypeCol = -1, doTypeCol = -1;
    public static int STstopIDCol = -1, blockIDCol = -1, arrivalTimeCol = -1;
    public static int transfersFrStopIDCol = -1, transfersToStopIDCol = -1, transferTypeCol = -1, transferMinTransferTimeCol = -1;
    public static boolean transferFileExists = true;
    public static String stopsFile = null, stopTimesFile = null, tripsFile = null, transfersFile = null;
    public static List<String> tripsList = new ArrayList<String>();
    public static boolean ignoreHeadways = false; // Timer starts when you start walking, not at startTime, will let you ride for up to endTime - startTime minutes
    public static int maxJourneyTime = 60; // This is meaningless if ignoreHeadways == false
    public static boolean allowTransfers = true;
    public static String rideLogFile = "ridelog.csv";
    public static String outputFile = "FinalTable.csv";
    public static String ignoreStopList = ","; //Comma separated string with comma at each end

    public static void main(String[] args) {
        //args are gtfs directory, startlat, startlon, starttime, endtime, ignore headways ("yes" ignores, anything else does not ignore)
        //start coords are the point you're travelling from
        //start and end times are window you have to make the trip.  Must arrive at stop before endtime
        long initTime = System.currentTimeMillis(); //mS since some date in 1970
        try {
            String gtfsDir = args[0];

            gtfsDir = gtfsDir.replace(Character.toString((char) 92), Character.toString((char) 47) + Character.toString((char) 47));
            //Replace '\' (92) with '//' (47)
            if (!gtfsDir.endsWith(Character.toString((char) 47) + Character.toString((char) 47))) {
                gtfsDir += Character.toString((char) 47) + Character.toString((char) 47);
            } // Add '//' to the end
            System.out.println("GTFS directory: " + gtfsDir);
            double pointLat = Double.parseDouble(args[1]);
            double pointLon = Double.parseDouble(args[2]);
            startTime = timeToMam(args[3]);
            endTime = timeToMam(args[4]);

            dealWithArgs(args);

            stopsFile = gtfsDir + "stops.txt";
            stopTimesFile = gtfsDir + "stop_times_mod.txt"; //added block_id, sorted by blockid then departure, removed unwanted calendars
            tripsFile = gtfsDir + "trips.txt";
            transfersFile = gtfsDir + "transfers.txt";

            findStopTimesColumns();
            loadStopsDataToMemory(pointLat, pointLon);
            initiateRidelog(rideLogFile);
            goThruEachStop();
            outputFinalTable(outputFile);
            int elapsedTime = (int) (System.currentTimeMillis() - initTime) / (1000 * 60); //Minutes: (int) rounds down I believe, whatever
            System.out.println("Finished in (hh:mm) " + mamToTime(elapsedTime));
        } catch (Exception e) {
            System.out.println();
            System.out.println("Isochrones.jar");
            System.out.println("Syntax is java -jar Isochrones.jar gtfsDir startPointLat startPointLng startTime endTime optionalArgs");
            System.out.println("See ReadMe for more information.");
        }
    }

    public static void outputFinalTable(String outputFileName) {
        int stopRadius = 0, walkTime = 0;
        try {
            writeToCSV(outputFileName, "stop_id,arrival_time,departure_time,departure_stop_id,route,transfer_route,initial_walk_time,radius_ft", false);
        } catch (IOException e) {
            System.out.println("Error writing to output file.");
        }
        for (int i = 0; i < stopsArrival.length; i++) {
            if (stopsArrival[i] > 0) {
                walkTime = (int) (stopsDist[departureStopID[i]] / walkSpeed) + 1;
                if (ignoreHeadways) {
                    stopRadius = (int) ((maxJourneyTime - (stopsArrival[i] - stopsDeparture[i] + stopsDist[i] / walkSpeed)) * walkSpeed);
                } else {
                    stopRadius = (int) ((endTime - stopsArrival[i]) * walkSpeed);
                }
                stopRadius = Math.min(stopRadius, maxWalk);
                stopRadius = Math.max(stopRadius, 0); //Shouldn't be necessary
                try {
                    writeToCSV(outputFileName, Integer.toString(i) + "," + mamToTime(stopsArrival[i]) + "," + mamToTime(stopsDeparture[i])
                            + "," + Integer.toString(departureStopID[i]) + "," + routeTaken[i] + "," + transferRouteTaken[i] + ","
                            + Integer.toString(walkTime) + "," + Integer.toString(stopRadius), true);
                } catch (IOException e) {
                    System.out.println("Error writing to output file.");
                }
            }
        }
    }

    public static void findStopTimesColumns() {
        BufferedReader br = null;
        String csvLine = null, csvArray[];

        try {
            br = new BufferedReader(new FileReader(stopTimesFile));
            csvLine = br.readLine();
            csvArray = csvLine.split(",");

            for (int i = 0; i <= csvArray.length - 1; i++) {
                if (csvArray[i].equals("trip_id")) {
                    tripIDCol = i;
                }
                if (csvArray[i].equals("departure_time")) {
                    departureTimeCol = i;
                }
                if (csvArray[i].equals("arrival_time")) {
                    arrivalTimeCol = i;
                }
                if (csvArray[i].equals("stop_id")) {
                    STstopIDCol = i;
                }
                if (csvArray[i].equals("block_id")) {
                    blockIDCol = i;
                }
                if (csvArray[i].equals("route_short_name")) {
                    routeSNCol = i;
                }
                if (csvArray[i].equals("pickup_type")) {
                    puTypeCol = i;
                }
                if (csvArray[i].equals("drop_off_type")) {
                    doTypeCol = i;
                }
            }
            if (tripIDCol < 0 || departureTimeCol < 0 || arrivalTimeCol < 0 || STstopIDCol < 0
                    || blockIDCol < 0 || routeSNCol < 0 || puTypeCol < 0 || doTypeCol < 0) {
                System.out.println("One or more fields missing in stop_times_mod.txt");
            }
        } catch (FileNotFoundException e) {
            System.out.println("Could not find stop_times_mod.txt.");
        } catch (IOException e) {
            System.out.println("Could not open stop_times_mod.txt.");
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    System.out.println("Could not close stop_times_mod.txt.");
                }
            }
        }

        br = null;
        try {
            br = new BufferedReader(new FileReader(transfersFile));
            csvLine = br.readLine();
            csvArray = csvLine.split(",");

            for (int i = 0; i <= csvArray.length - 1; i++) {
                if (csvArray[i].equals("from_stop_id")) {
                    transfersFrStopIDCol = i;
                }
                if (csvArray[i].equals("to_stop_id")) {
                    transfersToStopIDCol = i;
                }
                if (csvArray[i].equals("transfer_type")) {
                    transferTypeCol = i;
                }
                if (csvArray[i].equals("min_transfer_time")) {
                    transferMinTransferTimeCol = i; //Field is optional
                }
            }
            System.out.println("Processed transfers.txt");
            if (transfersFrStopIDCol < 0 || transfersToStopIDCol < 0 || transferTypeCol < 0 || transferMinTransferTimeCol < 0) {
                System.out.println("One or more fields missing in transfers.txt");
            }
        } catch (FileNotFoundException e) {
            //transfers file doesn't exist, no problem
            transferFileExists = false;
            System.out.println("No transfers.txt.  That's ok.");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void initiateRidelog(String csvFile) {
        //Should check if file exists before overwriting
        try {
            writeToCSV(csvFile, "from_stop_id,to_stop_id,route_id,trip_id,block_id,departure,arrival,walk_time,"
                    + "transfer_stop_id,to_stop_id,tr_route_id,tr_trip_id,tr_block_id,tr_departure,arrival,tr_walk_time,", false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static int timeToMam(String milTime) {
        // Converts time string such as "14:33" or "14:33:44" to minutes after midnight
        // Must be in 24 hour time
        // Rounds to nearest minute
        int mamMinutes;
        int mamHours;
        if (milTime.split(":").length < 2) {
            return 0;
        }
        try {
            mamMinutes = Integer.parseInt(milTime.split(":")[1]);
            mamHours = Integer.parseInt(milTime.split(":")[0]);
            if (milTime.split(":").length >= 3) {
                if (Integer.parseInt(milTime.split(":")[2]) >= 30) {
                    mamMinutes += 1; // Rounds up if seconds are included
                }
            }
        } catch (NumberFormatException e) {
            return 0;
        }
        return mamHours * 60 + mamMinutes;
    }

    public static String mamToTime(int mamTime) {
        // This method converts minutes after midnight (int) to military time (string, "14:33")
        int hour = mamTime / 60; //int truncates (rounds down)
        int minute = mamTime - (hour * 60);
        String hourText = Integer.toString(hour);
        String minuteText = Integer.toString(minute);
        if (hourText.length() == 1) {
            hourText = "0" + hourText;
        }
        if (minuteText.length() == 1) {
            minuteText = "0" + minuteText;
        }
        return hourText + ":" + minuteText;
    }

    public static void writeToCSV(String outputCSV, String csvLine, boolean appendFile) throws IOException {

        BufferedWriter out = null;
        try {
            FileWriter fstream = new FileWriter(outputCSV, appendFile); //true tells to append data.
            out = new BufferedWriter(fstream);
            out.write(csvLine + System.lineSeparator());
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    public static void loadStopsDataToMemory(double pointLat, double pointLon) { //Also finds dist to each stop
        //This creates 3 arrays stopsDist, stopsLat, and stopsLong where in array[#], # is the stop ID
        //So obviously your stop IDs have to all be ints, no strings or whatever
        BufferedReader br = null;
        String csvLine, csvArray[];
        int stopsLatCol = 0, stopsLonCol = 0, stopsStopIDCol = 0, lineNum = 1;
        double stopLat, stopLon, stopDist; //stopDist is dist from starting point to stop
        int stopID = 0;
        pointLat = pointLat * Math.PI / 180;
        pointLon = pointLon * Math.PI / 180;

        try {
            br = new BufferedReader(new FileReader(stopsFile));
            while ((csvLine = br.readLine()) != null) {
                csvArray = csvLine.split(",");
                if (lineNum == 1) {
                    for (int i = 0; i <= csvArray.length - 1; i++) {
                        if (csvArray[i].equals("stop_id")) {
                            stopsStopIDCol = i;
                        }
                        if (csvArray[i].equals("stop_lat")) {
                            stopsLatCol = i;
                        }
                        if (csvArray[i].equals("stop_lon")) {
                            stopsLonCol = i;
                        }
                    }
                } else {
                    stopLat = Double.parseDouble(csvArray[stopsLatCol]) * Math.PI / 180;
                    stopLon = Double.parseDouble(csvArray[stopsLonCol]) * Math.PI / 180;
                    stopID = Integer.parseInt(csvArray[stopsStopIDCol]);

                    if (stopID > stopsDist.length - 1) { //Increases the size of stopsDist if needed
                        int[] newArray = new int[stopsDist.length];
                        System.arraycopy(stopsDist, 0, newArray, 0, stopsDist.length);
                        stopsDist = new int[stopID + 1];
                        System.arraycopy(newArray, 0, stopsDist, 0, newArray.length);
                        newArray = null;

                        double[] newDoubleArray = new double[stopsLat.length];
                        System.arraycopy(stopsLat, 0, newDoubleArray, 0, stopsLat.length);
                        stopsLat = new double[stopID + 1];
                        System.arraycopy(newDoubleArray, 0, stopsLat, 0, newDoubleArray.length);
                        newDoubleArray = null;

                        newDoubleArray = new double[stopsLon.length];
                        System.arraycopy(stopsLon, 0, newDoubleArray, 0, stopsLon.length);
                        stopsLon = new double[stopID + 1];
                        System.arraycopy(newDoubleArray, 0, stopsLon, 0, newDoubleArray.length);
                        newDoubleArray = null;
                    }

                    stopsLat[stopID] = stopLat;
                    stopsLon[stopID] = stopLon;
                    stopDist = Math.acos(Math.sin(pointLat) * Math.sin(stopLat)
                            + Math.cos(pointLat) * Math.cos(stopLat) * Math.cos(stopLon - pointLon)) * (6371000 / 0.3048);
                    stopDist = Math.max(stopDist, 1); //if stopsDist = 0, will assume stop does not exist
                    stopsDist[stopID] = (int) stopDist;
                }
                lineNum++;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        stopsArrival = new int[stopsDist.length];
        stopsDeparture = new int[stopsDist.length];
        routeTaken = new String[stopsDist.length];
        transferRouteTaken = new String[stopsDist.length];
        departureStopID = new int[stopsDist.length];
    }

    public static void goThruEachStop() {
        //findDistToAllStops[45] is the distance to stop ID 45 in feet
        //This method is the starting point for "riding the bus"
        //int lineNum = 1, stopID; //, latCol = 0, lonCol = 0, stopIDCol = 0, stopID;
        //String rtDir;
        //String outputLine;

        for (int stopID = 0; stopID <= stopsDist.length - 1; stopID++) {
            if (stopsDist[stopID] != 0 && stopsDist[stopID] <= maxWalk && !ignoreStopList.contains("," + stopID + ",")) {
                rideTheBus(stopID);
                tripsList.clear(); //Empties tripsList
            }
        }
    }

    public static void rideTheBus(int stopID) {
        //Go thru stop_times and find out where each trip hits the stop
        //Currently does not take calendar days into account, you have to manually cut out stop_times records you don't want
        BufferedReader br = null;
        String csvLine, csvArray[];
        int lineNum = 1;
        String tripID = null, blockID = null, routeShortName = null, outputLine = null;
        int STstopID = 0, departureTime = 0, arrivalTime = 0; //All times are minutes afer midnight
        boolean ridingBus = false, foundBetterTrip = false;
        int puType = 0, doType = 0; //0 = allowed, 1 = not allowed
        System.out.println("Riding from stop ID " + Integer.toString(stopID));

        int walkTime = (int) (stopsDist[stopID] / walkSpeed) + 1;//Adding one because (int) rounds down, plus margin of error
        //Setting the walkTime = to at least 1 also assures that stops right on top of origin get circles

        try {
            br = new BufferedReader(new FileReader(stopTimesFile));
            while ((csvLine = br.readLine()) != null) {
                csvArray = csvLine.split(",");
                if (lineNum == 1) {
                    // Do nothing - reading header rows of csv file
                } else {
                    puType = Integer.parseInt(csvArray[puTypeCol]);
                    doType = Integer.parseInt(csvArray[doTypeCol]);
                    if (Integer.parseInt(csvArray[STstopIDCol]) == stopID && !ridingBus) {
                        departureTime = timeToMam(csvArray[departureTimeCol]);
                        blockID = csvArray[blockIDCol];
                        routeShortName = csvArray[routeSNCol];
                        tripID = csvArray[tripIDCol];
                        if ((departureTime - walkTime >= startTime) && (departureTime <= endTime && puType == 0)) { // && !tripsList.contains(tripID)) {
                            //Including '&& !tripsList.contains(tripID' reduces processing time and produces the same travel times it creates very
                            //strange trips where people transfer for no reason
                            ridingBus = true;
                            System.out.println("  Riding route " + routeShortName + " block " + blockID + " at " + mamToTime(departureTime));
                            tripsList.add(tripID);
                        }
                    }
                    if (ridingBus) {
                        STstopID = Integer.parseInt(csvArray[STstopIDCol]);
                        arrivalTime = timeToMam(csvArray[arrivalTimeCol]);
                        if ((ignoreHeadways == false && arrivalTime > endTime)
                                || (ignoreHeadways == true && (arrivalTime - departureTime + walkTime > maxJourneyTime))
                                || !csvArray[blockIDCol].equals(blockID)) {
                            ridingBus = false;
                        } else {
                            if (ignoreHeadways) {
                                foundBetterTrip = (arrivalTime - departureTime + walkTime) <= (stopsArrival[STstopID] - stopsDeparture[STstopID] + stopsDist[stopID] / walkSpeed);
                            } else {
                                foundBetterTrip = arrivalTime <= stopsArrival[STstopID];
                            }
                            if (doType == 0 && stopID != STstopID && (foundBetterTrip || stopsArrival[STstopID] == 0)) {
                                //Note that I'm using "<=" so a non-transfer ride will override a transfer ride that takes the same time.
                                //This does not prioritize lower walk times at all so you could have people walking a long way for no reason
                                //Since I'm not doing a user-based trip planner but doing isochrones and potentially skims, I don't care.
                                stopsArrival[STstopID] = arrivalTime;
                                stopsDeparture[STstopID] = departureTime;
                                routeTaken[STstopID] = routeShortName;
                                transferRouteTaken[STstopID] = null;
                                departureStopID[STstopID] = stopID;
                                outputLine = Integer.toString(stopID) + "," + Integer.toString(STstopID) + ","
                                        + routeShortName + "," + tripID + "," + blockID + ","
                                        + mamToTime(departureTime) + "," + mamToTime(arrivalTime) + "," + Integer.toString(walkTime) + ","
                                        + "," + Integer.toString(STstopID) + ",,,,," + mamToTime(arrivalTime) + ",";
                                writeToCSV(rideLogFile, outputLine, true);
                            }
                            if (stopID != STstopID && allowTransfers && doType == 0 && (foundBetterTrip || ignoreHeadways)) {
                                //Note above line last &&, if counting headways you can skip transferring if a faster trip already got there
                                transferRide(stopID, STstopID, departureTime, arrivalTime, routeShortName, tripID, blockID, walkTime);
                            }
                        }
                    }
                }
                lineNum++;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void transferRide(int origStopID, int transferArriveID, int origTime, int transferTime,
            String origRouteShortName, String origTripID, String origBlockID, int origWalkTime) {
        //Go thru stop_times and find out where each trip hits the stop
        //Currently does not take calendar days into account

        BufferedReader trbr = null;
        String csvLine, csvArray[];
        int lineNum = 1, transferBoardID = transferArriveID; //transferBoardID is where you board the second bus
        String tripID = null, blockID = null, routeShortName = null, outputLine = null;
        int STstopID = 0, departureTime = 0, arrivalTime = 0, walkTime = 0;
        boolean ridingBus = false, foundBetterTrip = false;
        double transferArriveLat = stopsLat[transferArriveID];
        double transferArriveLon = stopsLon[transferArriveID];
        double transferBoardLat, transferBoardLon, transferDist;
        int puType = 0, doType = 0; //0 = allowed, 1 = not allowed

        for (transferBoardID = 0; transferBoardID <= stopsDist.length - 1; transferBoardID++) {

            if (stopsDist[transferBoardID] != 0) {
                transferBoardLat = stopsLat[transferBoardID];
                transferBoardLon = stopsLon[transferBoardID];
                transferDist = Math.acos(Math.sin(transferBoardLat) * Math.sin(transferArriveLat) + Math.cos(transferBoardLat) * Math.cos(transferArriveLat) * Math.cos(transferBoardLon - transferArriveLon)) * (6371000 / 0.3048); //Feet
                walkTime = 2 + (int) (transferDist / walkSpeed);//Adding two minutes because (int) rounds down, plus margin of error 
                lineNum = 1;
                if (transferDist <= maxWalk / 2) { //Considering transfer max walk to be half of normal max walk
                    try {
                        trbr = new BufferedReader(new FileReader(stopTimesFile));
                        while ((csvLine = trbr.readLine()) != null) {
                            csvArray = csvLine.split(",");
                            if (lineNum == 1) {
                                //Do nothing
                            } else {
                                puType = Integer.parseInt(csvArray[puTypeCol]);
                                doType = Integer.parseInt(csvArray[doTypeCol]);
                                if (Integer.parseInt(csvArray[STstopIDCol]) == transferBoardID && !ridingBus) {
                                    departureTime = timeToMam(csvArray[departureTimeCol]);
                                    blockID = csvArray[blockIDCol];
                                    routeShortName = csvArray[routeSNCol];
                                    tripID = csvArray[tripIDCol];
                                    if ((departureTime - walkTime >= transferTime) && (departureTime <= endTime)
                                            && puType == 0 && !tripsList.contains(tripID)
                                            && transferOK(transferArriveID, transferBoardID, transferTime, departureTime)) {
                                        ridingBus = true;
                                        tripsList.add(tripID);
                                    }

                                }
                                if (ridingBus) {
                                    STstopID = Integer.parseInt(csvArray[STstopIDCol]);
                                    arrivalTime = timeToMam(csvArray[arrivalTimeCol]);
                                    if ((ignoreHeadways == false && arrivalTime > endTime)
                                            || (ignoreHeadways == true && (arrivalTime - origTime + origWalkTime > maxJourneyTime))
                                            || !csvArray[blockIDCol].equals(blockID)) {
                                        ridingBus = false;
                                    } else {
                                        if (ignoreHeadways) {
                                            foundBetterTrip = (arrivalTime - origTime + origWalkTime) < (stopsArrival[STstopID] - stopsDeparture[STstopID] + stopsDist[origStopID] / walkSpeed);
                                        } else {
                                            foundBetterTrip = arrivalTime < stopsArrival[STstopID];
                                        }
                                        if (doType == 0 && transferBoardID != STstopID && (foundBetterTrip || stopsArrival[STstopID] == 0)) {
                                            stopsArrival[STstopID] = arrivalTime;
                                            stopsDeparture[STstopID] = origTime;
                                            routeTaken[STstopID] = origRouteShortName;
                                            transferRouteTaken[STstopID] = routeShortName;
                                            departureStopID[STstopID] = origStopID;
                                            outputLine = Integer.toString(origStopID) + "," + Integer.toString(transferArriveID) + ","
                                                    + origRouteShortName + "," + origTripID + "," + origBlockID + ","
                                                    + mamToTime(origTime) + "," + mamToTime(transferTime) + "," + Integer.toString(origWalkTime) + ","
                                                    + Integer.toString(transferBoardID) + "," + Integer.toString(STstopID) + ","
                                                    + routeShortName + "," + tripID + "," + blockID + ","
                                                    + mamToTime(departureTime) + "," + mamToTime(arrivalTime) + "," + Integer.toString(walkTime);
                                            writeToCSV(rideLogFile, outputLine, true);
                                        }
                                    }
                                }
                            }
                            lineNum++;
                        }
                        trbr.close();
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (trbr != null) {
                            try {
                                trbr.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
    }

    public static void dealWithArgs(String[] args) {
        String maxJnStr = "";
        String newMaxJn = "";

        for (int i = 5; i < args.length; i++) {
            boolean understand = false;
            if (args[i].toLowerCase().equals("ignorewait")) {
                ignoreHeadways = true; // ignoreheadways by default is false
                understand = true;
                System.out.println("Ignore wait set to TRUE.");
            }

            if (args[i].substring(0, Math.min(10, args[i].length())).toLowerCase().equals("walkspeed=")) {
                String newWalkSpd = args[i].substring(10, args[i].length());
                try {
                    walkSpeed = Double.parseDouble(newWalkSpd) * 60; //fpm
                    System.out.println("Walk speed changed to " + newWalkSpd + " feet per second.");
                } catch (NumberFormatException nfe) {
                    System.out.println("Walk speed " + newWalkSpd + " not recognized as number! Default is 3 fps.");
                }
                understand = true;
            }

            if (args[i].toLowerCase().equals("notransfers")) {
                allowTransfers = false;
                System.out.println("Transfers NOT allowed.");
                understand = true;
            }

            if (args[i].substring(0, Math.min(8, args[i].length())).toLowerCase().equals("outfile=")) {
                String newFile = args[i].substring(8, args[i].length());
                if (newFile.length() >= 5 && newFile.substring(newFile.length() - 4, newFile.length()).equals(".csv")) {
                    outputFile = newFile;
                    System.out.println("Output file changed to " + newFile + ".");
                } else {
                    System.out.println("Output file " + newFile + " not recognized! Default is FinalTable.csv");
                }
                understand = true;
            }

            if (args[i].substring(0, Math.min(11, args[i].length())).toLowerCase().equals("maxjourney=")) {
                newMaxJn = args[i].substring(11, args[i].length());
                try {
                    maxJourneyTime = Integer.valueOf(newMaxJn); //minutes
                    maxJnStr = "Max journey changed to " + newMaxJn + " minutes.";
                } catch (NumberFormatException nfe) {
                    maxJnStr = "Max journey " + newMaxJn + " not recognized as number! Default is 60 minutes.";
                }
                understand = true;
            }

            if (args[i].substring(0, Math.min(8, args[i].length())).toLowerCase().equals("maxwalk=")) {
                String newMaxWk = args[i].substring(8, args[i].length());
                try {
                    maxWalk = Integer.valueOf(newMaxWk); //feet
                    System.out.println("Max walk changed to " + newMaxWk + " feet.");
                } catch (NumberFormatException nfe) {
                    maxJnStr = "Max walk " + newMaxWk + " not recognized as number! Default is 1320 feet.";
                }
                understand = true;
            }

            if (args[i].substring(0, Math.min(11, args[i].length())).toLowerCase().equals("ignorestop=")) {
                ignoreStopList = "," + args[i].substring(11, args[i].length()) + ","; // Comma sep string, NOT array
                understand = true;
            }

            if (!understand) {
                System.out.println("Do not understand: " + args[i]);
            }
        }

        if (maxJnStr.length() > 1) {
            if (!ignoreHeadways) {
                maxJnStr = "Max journey " + newMaxJn + " ignored unless you specity IGNOREWAIT.";
            }
            System.out.println(maxJnStr);
        }
    }

    public static boolean transferOK(int frStopID, int toStopID, int offTime, int onTime) {

        String csvLine, csvArray[];
        int lineNum = 1;
        BufferedReader br = null;

        if (!transferFileExists) {
            return true;
        }

        try {
            br = new BufferedReader(new FileReader(transfersFile));
            while ((csvLine = br.readLine()) != null) {
                csvArray = csvLine.split(",");
                if (lineNum == 1) {
                    //Do nothing, headings parsed in different method
                } else {
                    if (Integer.parseInt(csvArray[transfersFrStopIDCol]) == frStopID && Integer.parseInt(csvArray[transfersToStopIDCol]) == toStopID) {
                        int transferType = Integer.parseInt(csvArray[transferTypeCol]);
                        double minTransferTime = -1;
                        //System.out.println(transferMinTransferTimeCol + " of " + Integer.toString(csvArray.length) + " ");
                        //System.out.println("line " + lineNum + " stops " + frStopID + "-" + toStopID);
                        if (transferMinTransferTimeCol != -1) {
                            try {
                                minTransferTime = Double.parseDouble(csvArray[transferMinTransferTimeCol]) / 60;
                            } catch (NumberFormatException e) {
                                //Can't parse int, most likely null. minTransferTime remains -1
                            } catch (ArrayIndexOutOfBoundsException e) {
                                //This should never happen but sometimes it does. minTransferTime remains -1
                            }
                        }
                        if (transferType == 0 || transferType == 1) {
                            //System.out.println("Stops " + frStopID + "-" + toStopID + " Transfer 0 or 1 ok");
                            return true;
                        }
                        if (transferType == 2) {
                            //System.out.print("Stops " + frStopID + "-" + toStopID + " " + Integer.toString(onTime - offTime) + " of " + minTransferTime);
                            if (onTime - offTime >= minTransferTime) {
                                //System.out.println(" Ok");
                                return true;
                            } else {
                                //System.out.println(" Can't make transfer");
                                return false;
                            }
                        }
                        if (transferType == 3) {
                            //System.out.println("Transfer not allowed " + frStopID + " to " + toStopID);
                            return false;
                        }
                    }
                }
                lineNum++;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        //If made it through the transfers.txt and didn't find the stop-stop pair, it does not exist
        return true;
    }
}
