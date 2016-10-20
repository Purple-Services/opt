package purpleOpt;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.Map.Entry;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.text.ParseException;	// for SimpleDateFormat to UnixTime
import java.text.SimpleDateFormat;  // for SimpleDateFormat <--> UnixTime conversion
import java.lang.Math;

/*
General INPUT (for major functions):
	"orders":
		[order ID]:
			"lat" (Double);
			"lng" (Double);
			"id" (String);
			"courier_id" (String); // "" (empty string) if no courier is assigned
			"zones" (List<Integers>);
			"gas_type" (String);
			"gallons" (Double);
			"target_time_start" (Integer);
			"target_time_end" (Integer);
			"status" (String);
			"status_times" (HashMap<String,Long>):
				String: (Long);
	"couriers":
		[courier ID]:
			"lat" (Double);
			"lng" (Double);
			"connected" (Boolean);
			"last_ping" (Integer);
			"zones" (List<Integers>);
	"human_time_format": (optional) true / false (default);
	"current_time": (optional) SimpleDateFormat (if human_time_format is true) or UnixTime (if human_time_format is false)
	"verbose_output": (optional) true / false (default); if true, output will add the following fields:
	                   ->[order ID]->status, ->sorted_order_list, ->cluster_list, ->unprocessed_list, ->google_api_calls, ->google_duration_cache
	"simulation_mode": (optional) true / false (default); if true, google API calls will use departure_time=currTime+(52 weeks) and google_duration_cache will not be cleaned
	"google_api_calls": (optional) Integer; if not specified, it will be reset to 0
	"google_duration_cache": (optional) HashMap:
		[org_lat,org_lng,dest_lat,dest_lng] (Double[]):
			[timestamp] (Long) : [duration_in_traffic] (Long);
 */

/* int (Integer) vs long (Long)
 * use int (Integer) for: size, iteration index, comparator, those 
 * use long (Long) for: time, duration
 */

public class PurpleOpt {

	/*--- global parameters --*/

	/* global printing switch */
	static boolean bPrint = false; // CAUTION, use false for release
	/* Google API key */
	// static  google_api_key = "AIzaSyAFGyFvaKvXQUKzRh9jQaUwQnHnkiHDUCE"; // Wotao's key CAUTION, disable for release
	static String google_api_key = "AIzaSyCd_XdJsSsStXf1z8qCWITuAsppr5FoHao"; // Purple's key
	/* the radius used to test same-location orders */
	static double sameLocOrderRadius = 0.0002; // this value roughly equals ~10 yards; NOTE: the actual distance depends on the latitude of the city
	/* the radius used to test nearby orders */
	static double nearbyOrderRadius = 0.001; // this value roughly equals a street block; NOTE: the actual distance depends on the latitude of the city
	/* average servicing minutes */
	static long mins7_5GallonOrder = 18; // 18 minutes for 7.5 gallons
	static long mins10GallonOrder = 20; // 20 minutes for 10 gallons
	static long mins15GallonOrder = 25; // 25 minutes for 15 gallons 
	static long minsGenericOrder = 25;  // 25 minutes for other orders 
	/* serving-time discount factor for a nearby order (a courier can directly walk to) */
	static double servicingTimeFactorForNearbyOrder = 3.0/4.0; // ".0" is important
	/* travel time's factor in score computation, minimal is 1 (no penalty) */
	static double travel_time_factor = 3.5;
	/* the factor that converts l1 distance of lat-lng to driving seconds during artificial time computation */
	static double l1ToDistanceTimeFactor = 150*100;
	/* time delay for a currently working but non-connected courier */
	static long not_connected_delay = 15; // minutes 
	/* order due in less than 45 minutes is always urgent */
	static long minsUrgencyThreshold = 45; // minutes
	/* urgency threshold for classifying dangerous orders and urgent orders in sort incomplete orders */
	static double urgencyThreshold = 0.8;
	/* estimated l1 distance threshold for locations equaling in search saved google distance*/
	static double sameLocationTolerance = 0.0005; // this value roughly equals half a street block
	/* the max number of orders in one cluster */
	static long maxNumOrdersPerCluster = 4;
	
	/* current time */
	static long currTime;
	/* human time format switch (only for input and output) */
	static boolean human_time_format = false; // if true, input and output will use human readable time format
	/* working time, which is a specific future time for simulation and current time for realistic use */
	static boolean simulation_mode = false;
	/* verbose output switch */
	static boolean verbose_output = false;
	/* number of google API calls */
	static int google_api_calls = 0;
	
	/* save Google distance according to origin lat-lng and dest lat-lng
	 * HashMap:
		[org_lat,org_lng,dest_lat,dest_lng] (Double[]):
			[timestamp] (Long) : [duration_in_traffic] (Long);
	*/
	static HashMap<Double[], Object> google_duration_cache = new HashMap<Double[], Object>();
	/* duration of validation for google distance saved*/
	static long google_duration_valid_limit = 15 * 60;
	/*
    INPUT:
      general input (see above)
    OUTPUT: a LinkedHashmap
        [order ID]: {
          "tag": [(String) for "unassigned" orders, either "late", "urgent", or "normal"; for others, null] 
          "courier_id": [(String) suggested courier id],
          "new_assignment": [(boolean) true if it is a new suggested assignment; false if it is an existing assignment],
          "courier_pos": [(Integer) the position of the order in the courier's queue (1 based); null if cannot be determined]
          "etf": [(Integer) estimated finish time; null if cannot be computed],
          "cluster_first_order": [(String) null if not in a cluster; otherwise, the ID of the first order in the cluster, possibly this order itself]
          "status_at_input": (if verbose) [(String) the same status given in the input]
          "info": [(String) information]
          }
        sorted_order_list: (if verbose) the list of orders internally sorted, given in order_id's
        cluster_list: (if verbose) the list of order clusters, given in order_id's
        unprocessed_list: (if verbose) the list of unprocessed orders, given in order_id's
        google_api_calls: (if verbose) the number of google API calls made
        google_duration_cache: (if verbose) the google duration cache
    NOTE: if there is no unassigned order in the input, an empty LinkedHashmap (instead of null) will be returned
	}
	 */
	@SuppressWarnings("unchecked")
	public static LinkedHashMap<String, Object> computeSuggestion(HashMap<String,Object> input) {

		// read input
		Object value = input.get("human_time_format");
		if (value == null)
			human_time_format = false;
		else if (value instanceof Boolean)
			human_time_format = (boolean) value;
		else
			throw new IllegalArgumentException();
		
		// get current time from either the input or, if missing from the input, the system
		currTime = getCurrUnixTime(input);
		
		// get "simulation_mode" from input
		value = input.get("simulation_mode");
		if (value == null)
			simulation_mode = false;
		else if (value instanceof Boolean)
			simulation_mode = (boolean) value;
		else
			throw new IllegalArgumentException();
		
		// get "verbose_output" from input
		value = input.get("verbose_output");
		if (value == null)
			verbose_output = false;
		else if (value instanceof Boolean)
			verbose_output = (boolean) value;
		else
			throw new IllegalArgumentException();		
		
		// get initial "google_api_calls" from input
		value = input.get("google_api_calls");
		if (value == null)
			google_api_calls = 0;
		else if (value instanceof Long || value instanceof Integer)
			google_api_calls = (int) value;
		else
			throw new IllegalArgumentException();	
		
		// get initial "google_duration_cache" from input; if null, do nothing
		value = input.get("google_duration_cache");
		if (value != null){
			if (value instanceof HashMap)
				google_duration_cache = (HashMap<Double[], Object>) value;
			else
				throw new IllegalArgumentException();
		}
		
		// initialize an empty output HashMap
		LinkedHashMap<String,Object> outHashMap = new LinkedHashMap<>();

		if(!simulation_mode){
			// remove cached distances that are out of date
			// in simulation mode, all distances are cached for future re-simulation
			googleDistanceCacheClean(currTime);
		}
		
		// obtain orders from the input
		HashMap<String, Object> orders = (HashMap<String, Object>) input.get("orders");

		// return with the empty hashmap if there is no unassigned orders
		if (!bExistUnassignedOrder(orders))
			return outHashMap;
		
		// remove all completed and cancelled orders; keep only incomplete orders
		filterIncompleteOrders(orders);
		
		// initialize output HashMap; add some fields to both output and input "orders" for future use
		outputInitialize(outHashMap, orders);

		// obtain couriers from the input
		HashMap<String, Object> couriers = (HashMap<String, Object>) input.get("couriers");

		/* for each courier add a field "valid" (true or false); true means we can figure out when/where he will finish his last order, or is currently free, and thus we can assign him new orders; 
		 *                  add a field "assigned_orders" with his orders in the right ordering: servicing < enroute < (accepted or assigned), regardless "valid"
		 */
		courierValidation(couriers, orders);

		// for each order, add a field "cluster" (true or false); it gets false if the order is assigned to an invalid courier, or to a valid courier but not its last order
		// if false, then the order will not be clustered with any other order(s) with the only exception that it is at the same-location with another order 
		clusterValidation(couriers, orders);

		/* compute finish status of valid couriers
		 * and set "courier_pos", and "etf" for each "assigned", "accepted", "enroute" or "servicing" order
		 */
		setFinishStatus(couriers, orders, currTime);

		// Sort all incomplete orders ("unassigned", "assigned", "accepted", "enroute", "servicing")
		List<HashMap<String, Object>> sorted_orders = sortIncompleteOrders(orders,couriers,currTime, outHashMap);

		// save sorted_order_list if the output is verbose
		if (verbose_output){
			// copy sorted_orders to sorted_list for future print
			List<String> sorted_order_list = getOrderIdListFromListOfOrders(sorted_orders);
			// save sorted_order_list
			outHashMap.put("sorted_order_list", sorted_order_list);
		}
		
		// initialize an empty cluster_list for output
		List<List<String>> cluster_list = new ArrayList<>();
		
		// cluster orders and assign orders/clusters to couriers
		assignOrders(cluster_list, orders, sorted_orders, couriers, currTime);
		
		// save cluster_list if verbose
		if (verbose_output)
			outHashMap.put("cluster_list", cluster_list);
		
		// extract information from orders to outHashMap
		outputUpdate(orders, outHashMap);
		
		if (verbose_output){
			// collect unprocessed orders
			outHashMap.put("unprocessed_list", sorted_orders);
			// number of google API calls
			outHashMap.put("google_api_calls", google_api_calls);
			// collect google distance
			outHashMap.put("google_duration_cache", google_duration_cache);
		}
		
		return outHashMap;
	}
	
	// remove cached distances that are out of date
	@SuppressWarnings("unchecked")
	static void googleDistanceCacheClean(long currTime){
		// traverse all the entries of the type: <lat-lng, HashMap<timestamp, cached_google_duration>>
		Iterator<Entry<Double[], Object>> it = google_duration_cache.entrySet().iterator();
		while(it.hasNext()){
			Map.Entry<Double[], Object> pair =  it.next();	// get the next entry
			HashMap<Long, Long> record = (HashMap<Long, Long>) pair.getValue();	// get the HashMap<timestamp, cached_google_duration> of the entry, which is a record
			Iterator<Long> record_it = record.keySet().iterator();	// get the record's iterator
			while (record_it.hasNext()){
				if (currTime - record_it.next() > google_duration_valid_limit)	// compare current time to the timestamp of the record's entry
					record_it.remove();	// removing from the key removes the entry from the record 
			}
			if (record.size() == 0)
				it.remove();	// remove the record if its HashMap has no entry 
		}
	}

  	// Based on the computation, update output LinkedHashMap
	@SuppressWarnings("unchecked")
	static void outputUpdate(HashMap<String, Object> orders, LinkedHashMap<String, Object> outHashMap){
		for(String order_key: orders.keySet()){
			LinkedHashMap<String, Object> output_order = (LinkedHashMap<String, Object>)outHashMap.get(order_key);
			HashMap<String, Object> order = (HashMap<String, Object>)orders.get(order_key);
			output_order.put("new_assignment", order.get("new_assignment"));
			output_order.put("courier_id", order.get("courier_id"));
			output_order.put("courier_pos", order.get("courier_pos"));
			output_order.put("cluster_first_order", order.get("cluster_first_order"));
			if (order.get("etf") == null)
				output_order.put("etf", null);
			else
				output_order.put("etf", getTimeOutputInRightFormat(getLongFrom(order.get("etf"))));
			output_order.put("tag", order.get("tag"));
			output_order.put("info", order.get("info").toString());	// order.get("info") is a StringBuilder object
		}
	}
	
	@SuppressWarnings({ "unchecked"})
	static void assignOrders(List<List<String>> cluster_list, HashMap<String, Object> orders, List<HashMap<String, Object>>sorted_orders, HashMap<String, Object>couriers, long currTime){
		while (!sorted_orders.isEmpty()) {
			// initialize an empty(unknown) courier
			HashMap<String,Object> courier = null;
			// get an iterator of the list of sorted orders
			Iterator<HashMap<String,Object>> it = sorted_orders.listIterator();
			// call the first order from the list the base order
			HashMap<String,Object> base_order = it.next();
			String courier_id = (String)base_order.get("courier_id");
			
			// if the base order does not have an courier
			if(courier_id.equals("")){
				// find its best courier
				courier_id = courierScore(orders, couriers, base_order);
				// if the best courier is found
				if (!courier_id.equals("")){
					// update both the courier and order
					courier = (HashMap<String, Object>) couriers.get(courier_id);
					assignBaseOrder(base_order, courier);
					appendOrderInfo(base_order, "Assigned to a courier.");
				}
			}
			// the base order has been assigned to a courier, so obtain the courier
			else
				courier = (HashMap<String, Object>) couriers.get(courier_id);
			
			// ensure that the base order has been assigned a courier; otherwise, do nothing
			if(courier!=null){
				// initialize an empty cluster
				List<HashMap<String, Object>> cluster = new ArrayList<HashMap<String, Object>>();
				// do clustering, which will remove the base or all clustered orders from the list of sorted_orders
				cluster = doClustering(base_order, it, couriers, currTime);
				// record the cluster for output
				cluster_list.add(getOrderIdListFromListOfOrders(cluster));
				// if there are new order(s) in the cluster, assign them to the courier
				if (cluster.size()>1) {
					assignCluster(courier, cluster);
				}
			}
			else {
				// remove the base order
				it.remove();
			}
		}
	}

	// for a given order, compute the scores for the eligible couriers
	@SuppressWarnings({ "unchecked", "unused" })
	static String courierScore(HashMap<String, Object> orders, HashMap<String, Object> couriers, HashMap<String, Object> base_order){
		// initialize best score, finish time, and the corresponding courier's key
		boolean ontime_achieved = false;
		long best_score = 0;
		Long best_finish_time = 0L;
		String best_courier_key = "";
		// compute scores for all couriers
		for(String courier_key: couriers.keySet()) {
			// get the courier
			HashMap<String,Object> courier = (HashMap<String,Object>) couriers.get(courier_key);
			// consider a courier only if s/he can serve the order
			if (getBooleanFrom(courier.get("valid")) && bOrderCanBeServedByCourier(base_order,courier)) { 
				// compute score
				long start_time = getLongFrom(courier.get("finish_time")); // the time when the courier will finish all the assigned orders;
				long travel_time = timeDistantOrder(base_order, getDoubleFrom(courier.get("finish_lat")), getDoubleFrom(courier.get("finish_lng")));
				long finish_time = start_time + travel_time; // the total time for the new order
				long score = start_time + Math.round(travel_time_factor * (double)travel_time)
				+ computeCrossZonePenalty(base_order,courier,orders,couriers); // score also include cross-zone penalty
				boolean ontime = bOnTimeFinish(base_order, finish_time);
				// the approach below will obtain the best ontime courier and, if not found, then the best delay courier
				if (best_score == 0 							// first score
						|| (!ontime_achieved && score<best_score) 	// no courier can be ontime so far and this courier is better (whether it is ontime or not)  
						|| (ontime && score<best_score)				// this courier is both ontime and better than the best
						)
				{
					best_score = score;
					best_finish_time = finish_time;
					best_courier_key = courier_key;
					ontime_achieved = (ontime_achieved || ontime); // set to true once a courier is ontime
				}
			}
		}
		return best_courier_key;
	}
	
	@SuppressWarnings("unchecked")
	static List<HashMap<String, Object>> doClustering(HashMap<String, Object> base_order, Iterator<HashMap<String,Object>> it, HashMap<String, Object> couriers, long currTime){
		// initialize an empty cluster
		List<HashMap<String, Object>> cluster = new ArrayList<>();
		// obtain base_order's courier
		HashMap<String, Object> courier_for_base_order = (HashMap<String, Object>) couriers.get((String) base_order.get("courier_id"));
		// move the first order from the list to the cluster
		cluster.add(base_order);
		// remove the base order
		it.remove();
		// if the base_order can be clustered, then go through the remaining list for nearby orders while the cluster size is not exceeding the limit
		while (it.hasNext()) {
			
			HashMap<String,Object> comp_order = it.next();  // get the order to compare with the base_order
			// if the order satisfies conditions, then move it from the list to the cluster
			boolean bCourierNotValid = (!getBooleanFrom(courier_for_base_order.get("valid"))); 
			boolean bBaseOrderNotClusterable = (!getBooleanFrom(base_order.get("cluster")));
			boolean bMaxClusterSizeReached = (cluster.size() >= maxNumOrdersPerCluster);
			boolean bSameLoc = bSameLocOrder(base_order,comp_order);
			boolean bNearby = (bSameLoc || bNearbyOrder(base_order,comp_order));
			boolean bAlreadyAssinged = bAssignedToDifferentCouriers(base_order,comp_order);
			boolean bMissDeadlines = !bClusterSizeFitNew(cluster, comp_order, courier_for_base_order);
			
			// only nearby orders are considered
			if (bNearby)
			{
				if (bSameLoc)
					appendOrderInfo(comp_order, "Same-loc as another order.");
				else
					appendOrderInfo(comp_order, "Nearby another order, not same-loc.");
				
				if (bAlreadyAssinged) // skip if already assigned to another courier 
					appendOrderInfo(comp_order, "Not clustered as it was already assigned to a different courier.");
				else if ((!bSameLoc) && bMaxClusterSizeReached) // unless same-loc, ensure max cluster size is not reached yet
					appendOrderInfo(comp_order, "Not clustered since max cluster size is reached.");
				else if ((!bSameLoc) && bBaseOrderNotClusterable) // unless same-loc, ensure base_order is clusterable
					appendOrderInfo(comp_order, "Not clustered since base_order is not clusterable.");
				else if ((!bSameLoc) && bCourierNotValid) // unless same-loc, ensure base_order's courier is valid
					appendOrderInfo(comp_order, "Not clustered since base_order's courier is invalid.");
				else if ((!bSameLoc) && bMissDeadlines) // unless same-loc, ensure deadline won't violated
					appendOrderInfo(comp_order, "Not clustered due to deadline violation.");
				else {
					// Okay to cluster!
					appendOrderInfo(base_order, "Clustered an order.");
					appendOrderInfo(comp_order, "Get clustered.");
					// add to cluster
					cluster.add(comp_order);
					// get the base_order's id
					String base_order_id = (String)base_order.get("id");
					// update base_order->cluster_first_order to its own id
					base_order.put("cluster_first_order", base_order_id);
					// update comp_order->cluster_first_order to base_order's ID
					comp_order.put("cluster_first_order", base_order_id);
					// remove the comp_order for the list of sorted orders
					it.remove();
				}
			}
		}
		// reaching max cluster size, add info
		if (cluster.size() >= maxNumOrdersPerCluster)
			appendOrderInfo(base_order,"Clustered " + (cluster.size()-1) + " other orders. Max cluster size " + maxNumOrdersPerCluster + " reached");
		
		return cluster;
	}
	
	// assign a courier to an order (as a base order), adding the order to the end of courier's queue
	@SuppressWarnings("unchecked")
	static void assignBaseOrder(HashMap<String, Object> order, HashMap<String, Object> courier){
		// obtain courier's fields
		String courier_id = (String) courier.get("id");
		List<String> courier_assigned_orders = (List<String>) courier.get("assigned_orders");
		long start_time = getLongFrom(courier.get("finish_time"));
		// compute new time
		long finish_time = start_time + timeDistantOrder(order, getDoubleFrom(courier.get("finish_lat")), getDoubleFrom(courier.get("finish_lng")));
		// obtain order's fields
		String order_id = (String) order.get("id");

		// ensure that the order does not already have a courier
		if((order.get("courier_id")).equals("")){
			// update the courier
			courier_assigned_orders.add(order_id);
			courier.put("finish_time", finish_time);
			courier.put("finish_lat", getDoubleFrom(order.get("lat")));
			courier.put("finish_lng", getDoubleFrom(order.get("lng")));
			// update the order
			order.put("new_assignment", true);
			order.put("courier_id", courier_id);
			order.put("courier_pos", courier_assigned_orders.size());
			order.put("etf", finish_time);
		}
		return;
	}
	
	// assign a courier to a cluster of orders, adding the orders in the cluster to the end of courier's queue
	@SuppressWarnings("unchecked")
	static void assignCluster(HashMap<String, Object> courier, List<HashMap<String, Object>>cluster){
		// get courier's fields
		String courier_id = (String) courier.get("id");
		List<String> courier_assigned_orders = (List<String>) courier.get("assigned_orders");
		// initialize the order variable
		HashMap<String, Object> order = null;
		// get cluster iterator
		Iterator<HashMap<String,Object>> it = cluster.iterator();
		// get the base order
		order = it.next();
		// initialize etf to that of the base order
		long etf = getLongFrom(order.get("etf"));
		// for each non-base order
		while(it.hasNext()) {
			// get an order from the cluster
			order = it.next();
			// if this order has not been assigned 
			if((order.get("courier_id")).equals("")){
				// add the order to the courier
				courier_assigned_orders.add((String)order.get("id"));
				// update etf
				etf += timeNearbyOrder(order);
				// update this order
				order.put("new_assignment", true);
				order.put("courier_id", courier_id);
				order.put("courier_pos", courier_assigned_orders.size());
				order.put("etf", etf);
			}

			// update the best courier's finish_time/lat/lng
			courier.put("finish_time", etf);
			courier.put("finish_lat", getDoubleFrom(order.get("lat")));
			courier.put("finish_lng", getDoubleFrom(order.get("lng")));
		}
	}
	
	// from the org_list of orders, create a list with just the order id's
	static List<String> getOrderIdListFromListOfOrders(List<HashMap<String, Object>> org_list) {
		List<String> out_list = new ArrayList<String> ();
		
		Iterator<HashMap<String, Object>> it = org_list.iterator();
		while (it.hasNext()){
			HashMap<String, Object> order = it.next();
			out_list.add((String)(order.get("id")));
		}
		return out_list;
	}

	// Initialize the output LinkedHashMap and add some fields to both this output and the input HashMap "order"
	@SuppressWarnings("unchecked")
	static void outputInitialize(LinkedHashMap<String, Object> outHashMap, HashMap<String, Object> orders){
		for(String order_key: orders.keySet()){
			HashMap<String, Object> order = (HashMap<String, Object>)orders.get(order_key);
			// initialize an order_info HashMap
			LinkedHashMap<String, Object> order_info = new LinkedHashMap<String, Object>();
			// filling new_assignment
			order.put("new_assignment", false);
			order_info.put("new_assignment", false);
			// filling courier_id
			order_info.put("courier_id", order.get("courier_id"));
			// filling courier_pos and courier_etf
			order_info.put("courier_pos", null);
			order_info.put("etf", null);
			// filling tag: late, urgent, normal
			order_info.put("tag", null);
			// filling cluster_first_order;
			order.put("cluster_first_order", null);
			order_info.put("cluster_first_order", null);
			// filling info
			order.put("info", new StringBuilder());
			// filling status if verbose
			if (verbose_output)
				order_info.put("status_at_input", order.get("status"));
			// add order_info to outHashMap
			outHashMap.put(order_key, order_info);
		}
	}

	/* check whether there exists unassigned orders in orders */	
	@SuppressWarnings("unchecked")
	static boolean bExistUnassignedOrder(HashMap<String, Object> orders){
		for(Object order: orders.values()) {
			String order_status = (String) ((HashMap<String, Object>) order).get("status");
			if(order_status.equals("unassigned"))
				return true;
		}
		
		return false;
	}
	
	// remove any order with a status other than "unassigned", "assigned", "accepted", "enroute", or "servicing"
	@SuppressWarnings("unchecked")
	static void filterIncompleteOrders(HashMap<String, Object> orders){
		Iterator<String> it = orders.keySet().iterator();
		while (it.hasNext()) {
			String order_key = it.next(); // get next key
			HashMap<String,Object> order = (HashMap<String, Object>) orders.get(order_key); // get the order
			String order_status = (String) order.get("status"); // get the status string
			if (!(order_status.equals("unassigned") ||
				  order_status.equals("assigned") ||
				  order_status.equals("accepted") ||
				  order_status.equals("enroute")  ||
				  order_status.equals("servicing")) ){
				// remove the order
				it.remove();
			}
		}
	}


	/*
    OUTPUT:
	[order ID]: {
	  "etas": {
	     [courier id]: 450, // number of driving seconds away from the current location to the order
	     [courier id 2]: 615,
	     etc
	  }
	}
    The following keys are removed from under each order
	  "suggested_courier_id": [suggested courier id],
	  "expected_deadline_diff": [number of seconds, + for late, - for early]
	 */
	@SuppressWarnings("unchecked")
	public static HashMap<String, Object> computeDistance(HashMap<String,Object> input) {

		// get initial "google_api_calls" from input
		Object value = input.get("google_api_calls");
		if (value == null)
			google_api_calls = 0;
		else if (value instanceof Long || value instanceof Integer)
			google_api_calls = (int) value;
		else
			throw new IllegalArgumentException();	

		// get current time in the Unix time format
		// long currTime = System.currentTimeMillis() / 1000L; // not used any more

		// --- read data from input to structures that are easy to use ---
		// read the orders hashmap
		HashMap<String, Object> orders = (HashMap<String, Object>) input.get("orders");
		// read the couriers hashmap
		HashMap<String, Object> couriers = (HashMap<String, Object>) input.get("couriers");

		// create the output hashmap
		HashMap<String, Object> outHashmap = new HashMap<String, Object>();
		// structure:
		//   outHashmap(key: order_key; val: outOrder)
		//     outOrder(key: "suggested_courier_id"; null;
		//              key: "etas"; val: outETAs)
		//       outETAs(key: courier_key: val: eta_seconds)

		// create the inputs for calling GoogleDistanceMatrix
		List<String> listOrigins = new ArrayList<String>();	// store origin lat-lng
		List<String> listOriginKeys = new ArrayList<String>(); // store origin key (courier key)
		List<String> listDests = new ArrayList<String>();	// store destination lat-lng
		List<String> listDestKeys = new ArrayList<String>(); // store destination key (order key)

		/*-- add all the valid couriers to the list for GoogleDistanceMatrix --*/
		for(String courier_key: couriers.keySet()) {
			// get the order by ID (key)
			HashMap<String, Object> courier = (HashMap<String, Object>) couriers.get(courier_key);
			// check if the courier is valid
			if (bCourierValidLocation(courier)) {
				// get the courier lat and lng
				Double courier_lat = getDoubleFrom(courier.get("lat"));
				Double courier_lng = getDoubleFrom(courier.get("lng"));
				// add this courier to listOrigins, if the courier is not already there
				if (!listOriginKeys.contains(courier_key)) {
					// add the lat-lng of this courier to listOrigins
					listOrigins.add(courier_lat.toString()+","+courier_lng.toString());
					// add the courier key to listOriginKeys
					listOriginKeys.add(courier_key);
				}
			}
		}


		/*-- create an entry for each unassigned order in outHashmap and add it to listDests --*/
		for(String order_key: orders.keySet()) {
			// get the order object
			HashMap<String, Object> order = (HashMap<String, Object>) orders.get(order_key);
			// get the order status
			String order_status = (String) order.get("status");

			// check if the order is neither complete or cancelled
			if (!(order_status.equals("complete")||order_status.equals("cancelled"))) {
				// create a Hashmap for this order
				HashMap<String, Object> outOrder = new HashMap<String, Object>();
				// put this order in the output hashmap
				outHashmap.put(order_key, outOrder);

				// create a hashmap for ETAs
				HashMap<String, Integer> outETAs = new HashMap<String, Integer>();
				// put the ETAs to the order object
				outOrder.put("etas", outETAs);

				// get the order lat and lng
				Double order_lat = getDoubleFrom(order.get("lat"));
				Double order_lng = getDoubleFrom(order.get("lng"));

				// add this order to listDest, if not already there, for googleDistanceMatrix
				if (!listDestKeys.contains(order_key)) {
					// add the lat-lng of this order to listDests
					listDests.add(order_lat.toString()+","+order_lng.toString());
					// add this order's key to listDestKeys
					listDestKeys.add(order_key);
				}
			}
		}

		if (bPrint) {
			System.out.println(" #couriers: " + listOrigins.size() + "; #orders" + listDests.size());
			System.out.println();
		}

		// call getGoogleDistanceMatrix, if there are origin(s) and destination(s)
		if (!listOrigins.isEmpty() && !listDests.isEmpty()) {

			if (bPrint) 
				System.out.println("calling google ... ");

			// get ETAs by calling the function getGoogleDistanceMatrix
			// The result is a nested list. Each item of the outer list is an origin.
			List<List<Long>> listETAs = googleDistanceMatrixGetByHttp(listOrigins, listDests);

			if (bPrint) 
				System.out.println("google responded!");

			// write the ETAs to the hashmap
			// initialize listETAelements
			List<Long> listETAelements;
			// for each row (origin), and then for each column (destination)
			for(int i = 0; i < listETAs.size(); i++) {
				// get the corresponding courier key from listOriginKeys
				String courier_key1 = listOriginKeys.get(i);
				// get the list of ETAs for this courier
				listETAelements = listETAs.get(i);

				if (bPrint)
					System.out.print("  courier at " + listOrigins.get(i));

				// for each column (destination)
				for(int j = 0; j < listETAelements.size(); j++) {
					// get the corresponding order key from listDestKeys
					String order_key = listDestKeys.get(j);
					// from outHashmap, get the field "outOrder" corresponding to this order
					HashMap<String, Object> outOrder = (HashMap<String, Object>) outHashmap.get(order_key);
					// from outOrder, get the field "etas"
					HashMap<String, Integer> outETAs = (HashMap<String, Integer>) outOrder.get("etas");
					// write the order-courier ETA to outETAs
					outETAs.put(courier_key1, listETAelements.get(j).intValue());

					if (bPrint) {
						System.out.print(" order at " + listDests.get(j) + " ETA: " + listETAelements.get(j) + " seconds");
						System.out.println();
					}
				}
			}

		}

		// return outHashmap, if it is non-empty
		if (outHashmap.isEmpty())
			return null;
		else
			return (outHashmap);
	}
	
	/* return the all-pair google distance for a list of origins and destinations */
	public static List<List<Long>> googleDistanceMatrixGetByHttp(List<String> org_latlngs, List<String> dest_latlngs) {
		int nOrgs = org_latlngs.size();
		int nDests = dest_latlngs.size();

		if (nOrgs < 1 || nDests < 1)
			return null;

		// generate the origins string
		String strOrgs = "origins=";
		for (int i = 0; i < nOrgs; i++) {
			if (i > 0)
				strOrgs += "|";
			strOrgs += org_latlngs.get(i);
		}

		// generate the destinations string
		String strDests = "destinations=";
		for (int j = 0; j < nDests; j++) {
			if (j > 0)
				strDests += "|";
			strDests += dest_latlngs.get(j);
		}

		// generate request URL 
		String reqURL = "https://maps.googleapis.com/maps/api/distancematrix/json?" + strOrgs + "&" + strDests;
		reqURL += "&departure_time=now";	// this is not really useful
		reqURL += "&key=" + google_api_key;

		if (bPrint)
			System.out.println(reqURL);

		// prepare for the request
		URL url;
		HttpURLConnection conn;
		String outputString = "";

		// initialize the outer list
		List<List<Long>> mtxSeconds = new ArrayList<List<Long>>(nOrgs);
		List<Long> rowSeconds;

		try {
			// send the request to Google
			url = new URL(reqURL);
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("GET");

			google_api_calls += nOrgs*nDests;

			// retrieve the results
			BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				outputString += line;
			}

			// initialize the JSON parser
			JSONParser parser=new JSONParser();

			// check outer status;
			JSONArray json_array = (JSONArray)parser.parse("[" + outputString + "]");
			if(!((String)((JSONObject)json_array.get(0)).get("status")).equals("OK")){
				if (bPrint)
					System.out.println("Google result error");
				mtxSeconds = artificialDistanceCompute(org_latlngs, dest_latlngs);
				return mtxSeconds;
			}

			JSONArray rows = (JSONArray)((JSONObject) json_array.get(0)).get("rows"); 
			JSONObject row;	// each row corresponds to an origin (courier)
			JSONArray elements;	// each element corresponds to a destination (order)
			JSONObject element;
			String resp_status;
			Long resp_seconds;

			// check number of origins returned
			if(rows.size() != nOrgs){
				if (bPrint)
					System.out.println("Google result error for all origins");
				mtxSeconds = artificialDistanceCompute(org_latlngs, dest_latlngs);
				return mtxSeconds;
			}

			// loop through the results
			for (int i = 0; i < rows.size(); i++) {
				// get the row and its elements
				row = (JSONObject) rows.get(i);
				elements = (JSONArray) row.get("elements");

				// create element array for seconds
				rowSeconds = new ArrayList<Long> (nDests);

				// check the number of destinations returned for this origin
				if(elements.size() != nDests){
					if (bPrint)
						System.out.println("Google result error for the " + i + "-th origin");
					String origin = org_latlngs.get(i);
					for(int j = 0; j< nDests; j++) {
						String dest = dest_latlngs.get(j);
						rowSeconds.add(artificialDistanceCompute(origin, dest));
					}
				}
				else {
					// loop through the elements
					for (int j = 0; j < nDests; j++) {
						element = (JSONObject) elements.get(j);
						resp_status = (String)element.get("status");
						if (resp_status.equals("OK")) {
							resp_seconds = getLongFrom(((JSONObject)element.get("duration_in_traffic")).get("value"));
							rowSeconds.add(resp_seconds);
						}
						else {
							// go to artificial if the response status is not "OK"
							String origin = org_latlngs.get(i);
							String dest = dest_latlngs.get(j);
							rowSeconds.add(artificialDistanceCompute(origin, dest));
						}
					}
				}
				// add the row to the output nested list mtxSeconds
				mtxSeconds.add(rowSeconds);
			}
			return mtxSeconds;

		} catch (Exception e) {
			if (bPrint)
				System.out.println("Google connection error");
			mtxSeconds = artificialDistanceCompute(org_latlngs,dest_latlngs);
			return mtxSeconds;
		}
	}

	// single-origin single-dest artificial distance computing
	static Long artificialDistanceCompute(String org_latlngs, String dest_latlngs){
		//get origin position
		String[] strOrg = null;
		strOrg = org_latlngs.split(",");
		Double origin_lat = Double.parseDouble(strOrg[0]);
		Double origin_lng = Double.parseDouble(strOrg[1]);
		//get dest position
		String[] strDest = null;   
		strDest = dest_latlngs.split(",");
		Double dest_lat = Double.parseDouble(strDest[0]);
		Double dest_lng = Double.parseDouble(strDest[1]);
		//calculate distance
		Long dist = artificialDistanceCompute(origin_lat, origin_lng, dest_lat, dest_lng);
		return dist;

	}

	// multi-origins multi-dests artificial distance computing
	static List<List<Long>> artificialDistanceCompute(List<String> org_latlngs, List<String> dest_latlngs){
		int nOrgs = org_latlngs.size();
		int nDests = dest_latlngs.size();
		List<Long> rowSeconds;
		List<List<Long>> mtxSeconds = new ArrayList<List<Long>>(nOrgs);

		for(int i = 0; i < nOrgs; i++)
		{
			rowSeconds = new ArrayList<Long> (nDests);
			// abstract origin lat and lng from input string
			String[] strOrg = null;   
			strOrg = (org_latlngs.get(i)).split(",");
			// transform string to double;
			Double origin_lat = Double.parseDouble(strOrg[0]);
			Double origin_lng = Double.parseDouble(strOrg[1]);
			for(int j = 0; j<nDests; j++)
			{
				// abstract origin lat and lng from input string
				String[] strDest = null;   
				strDest = (dest_latlngs.get(j)).split(",");
				// transform string to double;
				Double dest_lat = Double.parseDouble(strDest[0]);
				Double dest_lng = Double.parseDouble(strDest[1]);

				rowSeconds.add(artificialDistanceCompute(origin_lat, origin_lng, dest_lat, dest_lng));
			}
			mtxSeconds.add(rowSeconds);
		}
		return mtxSeconds;
	}

	static long getCurrUnixTime(HashMap<String, Object>input){
		/* --- get current time in the Unix time format --- */
		long currTime = 0;
		Object value = (Object) input.get("current_time");
		
		if (value == null)
			// get the current system time
			currTime = System.currentTimeMillis() / 1000L;
		else if (human_time_format)
			// get the specified "current time" in human time format
			currTime = SimpleDateFormatToUnixTime((String)value);
		else
			// get the specified "current time" in the Unix time format
			currTime = getLongFrom(value);
		
		return currTime;
	}
	

	/* For each courier, set their status (lat,lng,time) when they finish their already-assigned orders)
	 * If they have no assigned order, use their current status.
	 * Related orders also get courier_id / courier_pos / etf
	 */
	@SuppressWarnings("unchecked")
	static void setFinishStatus(HashMap<String, Object> couriers, HashMap<String, Object> orders, Long currTime){
		for(String courier_key: couriers.keySet()) {
			// get the courier by their key
			HashMap<String, Object> courier = (HashMap<String, Object>) couriers.get(courier_key);
			// get finish time/lat/lng
			HashMap<String, Object> finish = computeFinishTimeLatLng(courier, orders, currTime);
			// add entries to the existing couriers hashmap for later use
			courier.put("finish_time", finish.get("finish_time"));
			courier.put("finish_lat", finish.get("finish_lat"));
			courier.put("finish_lng", finish.get("finish_lng"));

		}
	}

	

	/* --- go through the couriers and remove the invalid ones, because they cannot take orders --- */
	@SuppressWarnings("unchecked")
	static void courierValidation(HashMap<String, Object> couriers, HashMap<String, Object> orders){
		for(Object courier: couriers.values()){
			bCourierValid((HashMap<String, Object>) courier, orders);
		}
	}

	/* Compute finish_time, finish_lat, finish_lng for a valid courier 
	 * Related orders also get courier_id / courier_pos / etf
	 */
	@SuppressWarnings("unchecked")
	static HashMap<String,Object> computeFinishTimeLatLng(HashMap<String, Object> courier, HashMap<String, Object> orders, long startTime) {

		// exception handling for a courier without the field "valid" or is invalid, just in case
		Boolean bValid = getBooleanFrom(courier.get("valid"));
		// initialize assigned orders for the courier
		List<String> assigned_orders_keys = new ArrayList<String>();
		// if the courier is invalid but its first order is enroute or servicing, we should still compute this order's etf
		if (bValid == null || (!bValid)) {
			// get the courier's total assigned orders
			List<String> temp_list = (List<String>)courier.get("assigned_orders");
			// obtain the first order of this courier for computing its etf
			if (!temp_list.isEmpty()) {
				HashMap<String,Object> firstOrder = (HashMap<String, Object>) orders.get(temp_list.get(0));
				if ((firstOrder.get("status")).equals("enroute") || (firstOrder.get("status")).equals("servicing"))
					assigned_orders_keys.add(temp_list.get(0));
			}
		}
		else
			// get the courier's total assigned orders
			assigned_orders_keys = (List<String>)courier.get("assigned_orders");
		
		// initialize lat lng to the courier's current lat lng
		Double finish_lat = getDoubleFrom(courier.get("lat"));
		Double finish_lng = getDoubleFrom(courier.get("lng"));
		// initialize finish_time to the specified startTime
		Long finish_time = startTime;

		// check empty
		if (! assigned_orders_keys.isEmpty()) { // if it has assigned orders
			// get the first order, assumed to be the working order
			HashMap<String, Object> order = (HashMap<String, Object>) orders.get(assigned_orders_keys.get(0));

			// initialize the assigned order lat-lng as the first (working) order lat-lng
			Double order_lat = getDoubleFrom(order.get("lat"));
			Double order_lng = getDoubleFrom(order.get("lng"));
			
			if (bCourierAtOrderSite(order,courier))
				// TODO: when the courier is on-site, we should use the event-log time to determine the remaining servicing time
				finish_time += iOrderServingTime(order) / 2;
			else {
				if(bCourierValidLocation(courier))
					finish_time += googleDistanceGetByHttp(finish_lat, finish_lng, order_lat, order_lng)
					+ iOrderServingTime(order);
				else
					finish_time += iOrderServingTime(order) + not_connected_delay * 60;
			}

			// update courier first order's etf and position
			order.put("etf", finish_time);
			order.put("courier_pos", new Long(1L));
			
			// process the remaining assigned orders
			for (int i=1; i<assigned_orders_keys.size(); i++) { // i=1 means we start from the second order
				// get the order
				order = (HashMap<String, Object>) orders.get(assigned_orders_keys.get(i));

				// we are looking at an assigned order in the courier's queue
				Double prev_order_lat = order_lat;
				Double prev_order_lng = order_lng;
				// get the assigned order's lat and lng
				order_lat = getDoubleFrom(order.get("lat"));
				order_lng = getDoubleFrom(order.get("lng"));

				// check if two orders are nearby
				if (bNearbyOrderLatLng(prev_order_lat,prev_order_lng,order_lat,order_lng)) {
					// add a discounted servicing time, and skip traveling
					finish_time += timeNearbyOrder(order);
				}
				else {
					// add both traveling and servicing times
					finish_time += timeDistantOrder(order, prev_order_lat, prev_order_lng);
				}

				// tag the order with its assigned courier
				// order.put("courier_id", (String)courier.get("id")); // commented out because the courier_id should be already there
				order.put("etf", finish_time);
				order.put("courier_pos", new Long((long)(i+1)));
			}
			// update finish_lat / lng
			finish_lat = order_lat;
			finish_lng = order_lng;
		}
		
		// initialize output hash map
		HashMap<String,Object> outHashMap = new HashMap<>();

		// return for invalid courier
		if (bValid == null || (!bValid)) {
			// put null results into the output hashmap
			outHashMap.put("finish_time", null);
			outHashMap.put("finish_lat", null);
			outHashMap.put("finish_lng", null);
		}
		else{
			// put results into the output hashmap
			outHashMap.put("finish_time", finish_time);
			outHashMap.put("finish_lat", finish_lat);
			outHashMap.put("finish_lng", finish_lng);
		}
		
		// output return
		return outHashMap;
	}

	// determine whether two locations are close up to the tolerance
	static boolean googleDistanceZero(Double lat1, Double lng1, Double lat2, Double lng2, Double tol) {
		if ((lat1-lat2)*(lat1-lat2) + (lng1-lng2)*(lng1-lng2) <= tol*tol)
			return true;
		else
			return false;
	}

	// determine whether two orders are nearby or not
	static boolean bNearbyOrderLatLng(Double lat1, Double lng1, Double lat2, Double lng2) {
		return googleDistanceZero(lat1, lng1, lat2, lng2, nearbyOrderRadius);
	}
	
	/* decide whether two orders are considered nearby */
	static boolean bNearbyOrder(HashMap<String,Object> order1, HashMap<String,Object> order2) {
		return bNearbyOrderLatLng(getDoubleFrom(order1.get("lat")), getDoubleFrom(order1.get("lng")),
				getDoubleFrom(order2.get("lat")), getDoubleFrom(order2.get("lng")));
	}

	// determine whether two orders are at the same location or not
	static boolean bSameLocOrderLatLng(Double lat1, Double lng1, Double lat2, Double lng2) {
		return googleDistanceZero(lat1, lng1, lat2, lng2, sameLocOrderRadius);
	}
	
	/* decide whether two orders are considered same-location */
	static boolean bSameLocOrder(HashMap<String,Object> order1, HashMap<String,Object> order2) {
		return bSameLocOrderLatLng(getDoubleFrom(order1.get("lat")), getDoubleFrom(order1.get("lng")),
				getDoubleFrom(order2.get("lat")), getDoubleFrom(order2.get("lng")));
	}

	/* decide whether a courier has a valid location so it can take orders */
	public static boolean bCourierValidLocation(HashMap<String, Object> courier){
		// get the courier connection status
		Boolean connected = getBooleanFrom(courier.get("connected"));
		// get the courier lat and lng
		Double courier_lat = getDoubleFrom(courier.get("lat"));
		Double courier_lng = getDoubleFrom(courier.get("lng"));
		if (connected.booleanValue() && courier_lat != 0 && courier_lng != 0)
			return true;
		else 
			return false;

	}

	/* get courier's list of assigned orders (except completed and cancelled orders) */
	@SuppressWarnings("unchecked")
	static List<String> getAssignedOrderList(HashMap<String, Object>courier, final HashMap<String, Object>orders){
		String courier_id = (String)courier.get("id");
		List<String> assigned_order_list_of_courier = new ArrayList<String>();
		// get this courier's assigned orders
		for(String order_id: orders.keySet()){
			HashMap<String, Object> order = (HashMap<String, Object>)orders.get(order_id);
			String status = (String) order.get("status");
			String order_courier_id = (String)order.get("courier_id");
			// if an order belongs to the courier and it is either assigned, accepted, enroute or servicing
			if(order_courier_id.equals(courier_id) && (status.equals("assigned") || status.equals("accepted") || status.equals("enroute") || status.equals("servicing"))){
				// check if the assigned courier is the input one
				assigned_order_list_of_courier.add(order_id);
			}
		}
		
		// sort the orders by their status: servicing < enroute < (accepted or assigned)
		Collections.sort(assigned_order_list_of_courier, new Comparator<String>() {
			public int compare(String o1, String o2) {
				HashMap<String, Object> order1 = (HashMap<String, Object>) orders.get(o1);
				HashMap<String, Object> order2 = (HashMap<String, Object>) orders.get(o2);
				String o1_status = (String)order1.get("status");
				String o2_status = (String)order2.get("status");
				if ((o1_status.equals("accepted") || o1_status.equals("assigned")) && (o2_status.equals("servicing") || o2_status.equals("enroute")))
					// give priority to a working order over a non-standing order
					return 1;
				else if (o1_status.equals("enroute") && o2_status.equals("servicing"))
					// give priority to a servicing order over an enroute order
					return 1;
				else
					return -1;
			}});

		return assigned_order_list_of_courier;
	}

	/* for input courier add a field "valid" (true or false), 
	 *                   add a field "assigned_orders" with his orders in the right precedence
	 */
	@SuppressWarnings("unchecked")
	static void bCourierValid(HashMap<String, Object> courier, HashMap<String, Object> orders){
		// get assigned order list for this courier, ordered by status as servicing < enroute < (accepted or assigned)
		List<String> assigned_order_list = getAssignedOrderList(courier, orders);
		// add the list assigned_orders to each courier
		courier.put("assigned_orders", assigned_order_list);
		
		// has no assigned_orders and a valid location
		if(assigned_order_list.size() == 0 && bCourierValidLocation(courier)){
			courier.put("valid", true);
		}
		// with one assigned order
		else if(assigned_order_list.size() == 1){
			courier.put("valid", true);
		}
		// with two assigned orders
		else if(assigned_order_list.size() == 2){
			HashMap<String, Object> firstOrder = (HashMap<String, Object>)orders.get(assigned_order_list.get(0));
			// get the second assigned order's status
			HashMap<String, Object> secondOrder = (HashMap<String, Object>)orders.get(assigned_order_list.get(1));
			// if the first order is a working order
			if((firstOrder.get("status")).equals("enroute") || (firstOrder.get("status")).equals("servicing")){
				courier.put("valid", true);
			}
			// the second order is a working one
			else if((secondOrder.get("status")).equals("enroute") || (secondOrder.get("status")).equals("servicing")){
				// swap the two orders in the list
				List<String> assigned_orders= new ArrayList<String>();
				assigned_orders.add(assigned_order_list.get(1));
				assigned_orders.add(assigned_order_list.get(0)); 
				// the courier is valid for new assignment
				courier.put("valid", true);
			}
			// neither order has been started
			else{
				// the courier is invalid, but we cannot tell which order will be serviced last
				courier.put("valid", false);
			}
		}
		// either location invalid with no order, or having more than two assigned orders
		else
			// the courier is invalid
			courier.put("valid", false);
	}

	// for each order, add a field "cluster" (true or false); false if it is assigned to an invalid courier, or to a valid courier but not its last order
	@SuppressWarnings("unchecked")
	static void clusterValidation(HashMap<String, Object>couriers, HashMap<String, Object>orders){
		for(String courier_key: couriers.keySet()){
			HashMap<String, Object> courier = (HashMap<String, Object>)couriers.get(courier_key);
			// if the courier is invalid, then all of his assigned orders are invalid
			if(!getBooleanFrom(courier.get("valid"))){
				for(String order_id: (List<String>)courier.get("assigned_orders")){
					HashMap<String, Object> order = (HashMap<String, Object>) orders.get(order_id);
					order.put("cluster", false);
					appendOrderInfo(order, "Assigned previously. Its courier is invalid, not clusterable.");
				}
			}
			// else, the courier is valid
			else{
				List<String> assigned_order_list = (List<String>)courier.get("assigned_orders");
				int listSize = assigned_order_list.size();
				// but if the courier has more than two manager assigned orders, then we don't know which is the last one
				// if the courier has only one order, then it can be clustered
				for (int i = 0; i < (listSize - 1); i++) // those orders before the last cannot be clustered
				{
					HashMap<String, Object> order = (HashMap<String, Object>) orders.get(assigned_order_list.get(i));
					order.put("cluster", false);
					appendOrderInfo(order, "Assigned previously. Not its couriers' last order in queue, not clusterable");
				}
				if (listSize >= 1) // the last order can be clustered
				{
					HashMap<String, Object> order = (HashMap<String, Object>) orders.get(assigned_order_list.get(listSize-1));
					order.put("cluster", true);
					appendOrderInfo(order, "Assigned previously. As last order of its courier, can cluster other orders.");
				}
			}
		}
		// every unassigned order can be clustered
		for(String order_key: orders.keySet()){
			HashMap<String, Object> order = (HashMap<String, Object>)orders.get(order_key);
			if((order.get("status")).equals("unassigned")){
				order.put("cluster", true);
				appendOrderInfo(order, "New order, can cluster other orders or be clustered.");
			}
		}
		// ensure every order has a field "cluster", to avoid future nullPointerException
		clusterFieldCheck(orders);
		
		return;
	}
	
	// ensure every order has a field "cluster"
	@SuppressWarnings("unchecked")
	static void clusterFieldCheck(HashMap<String, Object> orders){
		for(String order_key: orders.keySet()){
			HashMap<String, Object> order = (HashMap<String, Object>)orders.get(order_key);
			if(!order.containsKey("cluster")){
				order.put("cluster", false);
			}
		}
		return;
	}

	static String UnixTimeToSimpleDateFormat(Long unixTime) {
		Date dateTime = new Date(unixTime * 1000L);
		SimpleDateFormat sdfFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
		sdfFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
		return sdfFormat.format(dateTime);
	}

	/* convert a UnixTime to a SimpleDateFormat at PDT */
	static String UnixTimeToSimpleDateFormatNoDate(Long unixTime) {
		Date dateTime = new Date(unixTime * 1000L);
		SimpleDateFormat sdfFormat = new SimpleDateFormat("HH:mm:ss z");
		sdfFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
		return sdfFormat.format(dateTime);
	}

	/* convert a SimpleDateFormat with time zone info to a UnixTime */
	static Long SimpleDateFormatToUnixTime(String sdfTime) {
		SimpleDateFormat sdfFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
		try {
			Date dateTime = sdfFormat.parse(sdfTime);
			return dateTime.getTime()/1000L;
		}
		catch (ParseException e) {
			e.printStackTrace();
		}
		return null;
	}

	/* remove the date from a SimpleDateFormat with time zone info */
	static String SimpleDateFormatRemoveDate(String sdfTime) {
		SimpleDateFormat sdfFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
		try {
			Date dateTime = sdfFormat.parse(sdfTime);
			SimpleDateFormat sdfFormatNoDate = new SimpleDateFormat("HH:mm:ss z");			
			sdfFormat.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
			return sdfFormatNoDate.format(dateTime.getTime());
		}
		catch (ParseException e) {
			e.printStackTrace();
		}
		return null;
	}

	// determine whether a courier is on the site of an order
	static boolean bCourierAtOrderSite(HashMap<String,Object> order, HashMap<String,Object> courier) {
		// true if the courier at the order site; false otherwise.
		String ordStatus = (String) order.get("status");
		if (ordStatus.equals("servicing"))
			return true;
		else
			return false;
	}

	// average servicing time in second by gallons
	static long iOrderServingTime(HashMap<String,Object> order) {
		double epsilon = 0.05; // 5% difference tolerance
		double gallons = Double.MAX_VALUE;
		
		// obtain gallon numbers
		gallons = getDoubleFrom(order.get("gallons"));
				
		if (nearlyEqual(gallons, 7.5, epsilon)) // 7.5 gallons
			return 60 * mins7_5GallonOrder;
		else if (nearlyEqual(gallons, 10.0, epsilon)) // 10 gallons
			return 60 * mins10GallonOrder;
		else if (nearlyEqual(gallons, 15.0, epsilon)) // 15 gallons
			return 60 * mins15GallonOrder;
		else
			return 60 * minsGenericOrder;

	}

	// search if the distance between two locations has been saved before
	@SuppressWarnings("unchecked")
	static long googleDistanceCacheSearch(Double origin_lat, Double origin_lng, Double dest_lat, Double dest_lng){
		for(Double[] org_dest_key : google_duration_cache.keySet()){
			Double saved_org_lat = org_dest_key[0];
			Double saved_org_lng = org_dest_key[1];
			Double saved_des_lat = org_dest_key[2];
			Double saved_des_lng = org_dest_key[3];

			// target origin and dest are close to a pair of saved records
			if(googleDistanceZero(origin_lat, origin_lng, saved_org_lat, saved_org_lng, sameLocationTolerance) &&
			   googleDistanceZero(dest_lat,   dest_lng,   saved_des_lat, saved_des_lng, sameLocationTolerance)   ){
				// get saved record in google_duration_cache
				HashMap<Long,Long> record = (HashMap<Long,Long>) google_duration_cache.get(org_dest_key);
				// look through all the time stamps
				for(Long timestamp : record.keySet()){
					// return the cache if its time stamp is with currTime +/- google_duration_valid_limit 
					if(Math.abs(currTime - timestamp) <= google_duration_valid_limit)
						return record.get(timestamp);
				}
				// do not remove record here to avoid concurrent modification
			}
		}
		// if not found in cache, return -1 (Note: returning 0 is not safe because 0 is a valid duration)
		return -1;
	}
	
	// given org_lat/lng and dest_lat/lng, look up google_duration_cache to find a roughly matching record; if not found, return null
	@SuppressWarnings("unchecked")
	static HashMap<Long,Long> googleDistanceLocationLookup(Double origin_lat, Double origin_lng, Double dest_lat, Double dest_lng){
		// traverse all pairs of org_lat/lng and dest_lat/lng
		for(Double[] org_dest_key : google_duration_cache.keySet()){
			Double saved_org_lat = org_dest_key[0];
			Double saved_org_lng = org_dest_key[1];
			Double saved_des_lat = org_dest_key[2];
			Double saved_des_lng = org_dest_key[3];
		
			// target origin and dest are close to a pair of saved records
			if(googleDistanceZero(origin_lat, origin_lng, saved_org_lat, saved_org_lng, sameLocationTolerance) &&
			   googleDistanceZero(dest_lat,   dest_lng,   saved_des_lat, saved_des_lng, sameLocationTolerance)){
				return (HashMap<Long,Long>)google_duration_cache.get(org_dest_key);
			}
		}
		// not found
		return null;
	}

	// after calling google API, save the distance
	static void googleDistanceAddToCache(long seconds, Double origin_lat, Double origin_lng, Double dest_lat, Double dest_lng){
		// check if the (rough) location already exists
		HashMap<Long,Long> record = googleDistanceLocationLookup(origin_lat, origin_lng, dest_lat, dest_lng);
		// if not, create a new record and add it to google_duration_cache
		if (record == null) {
			Double[] org_dest_key = {origin_lat,origin_lng,dest_lat,dest_lng};
			record = new HashMap<Long, Long>();
			google_duration_cache.put(org_dest_key, record);
		}
		
		// add the entry timestamp:duration to the record
		record.put(currTime, seconds);
	}

	/* return the google distance for a courier to an order */
	public static long googleDistanceGetByHttp(Double origin_lat, Double origin_lng, Double dest_lat, Double dest_lng) {

		// check if the origin and dest are the same place
		if (googleDistanceZero(origin_lat, origin_lng, dest_lat, dest_lng, sameLocationTolerance))
			return 0;

		// if not check if the distance has been saved, return -1 if not found
		long seconds = googleDistanceCacheSearch(origin_lat, origin_lng, dest_lat, dest_lng);
		
		if (seconds != -1)
			// found in the cache
			return seconds;
		else {
			// not found, so call Google for the distance

			// set courier as the origin
			String org = origin_lat.toString() + "," + origin_lng.toString();
			// set order as the destination
			String dest = dest_lat.toString() + "," + dest_lng.toString();

			// generate request URL 
			String reqURL = "https://maps.googleapis.com/maps/api/distancematrix/json?origins=" + org + "&destinations=" + dest;
			// under simulation_mode, use currTime+52 weeks because currTime is not the real current time
			if(simulation_mode)
				reqURL += "&departure_time=" + (currTime + 31449600);
			else
				reqURL += "&departure_time=now";

			reqURL += "&key=" + google_api_key;

			// debug display
			if (bPrint)
				System.out.println(reqURL);

			// send the request to google
			URL url;
			HttpURLConnection conn;
			String outputString = "";

			try {
				url = new URL(reqURL);
				conn = (HttpURLConnection) url.openConnection();
				conn.setRequestMethod("GET");
				conn.setConnectTimeout(5000);
				
				google_api_calls++;

				BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				String line;
				while ((line = reader.readLine()) != null) {
					outputString += line;
				}

				// initial JSON parser
				JSONParser parser=new JSONParser();
				JSONArray json_array = (JSONArray)parser.parse("[" + outputString + "]");
				JSONObject row_0_element_0 = (JSONObject)((JSONArray)((JSONObject)((JSONArray)((JSONObject) json_array.get(0)).get("rows")).get(0)).get("elements")).get(0);

				// check if "status" is "OK" in the JSON
				String resp_status = (String)row_0_element_0.get("status");
				if (resp_status.equals("OK")) {
					// parse JSON for the seconds
					Long resp_seconds = getLongFrom(((JSONObject)row_0_element_0.get("duration_in_traffic")).get("value"));
					seconds = resp_seconds.intValue();
					googleDistanceAddToCache(seconds, origin_lat, origin_lng, dest_lat, dest_lng);
				}
				else {
					if (bPrint)
						System.out.println("Google zero result");
					return artificialDistanceCompute(origin_lat, origin_lng, dest_lat, dest_lng); 
				}
			} catch (Exception e) {
				// e.printStackTrace();
				if (bPrint)
					System.out.println("Google connection error");
				return artificialDistanceCompute(origin_lat, origin_lng, dest_lat, dest_lng);
			}

			return seconds;
		}
	}

	/* used for offline and non-google distance computation */
	public static long artificialDistanceCompute(Double origin_lat, Double origin_lng, Double dest_lat, Double dest_lng) {
		return (long) Math.round(l1ToDistanceTimeFactor * (double)((Math.abs(origin_lat - dest_lat) + Math.abs(origin_lng - dest_lng))));
	}

	static long timeNearbyOrder(HashMap<String,Object> order) {
		return Math.round(servicingTimeFactorForNearbyOrder * (double)iOrderServingTime(order));
	}

	/* return the total time spent on an order that is away from the previous lat-lng location */
	static long timeDistantOrder(HashMap<String,Object> order, Double prev_lat, Double prev_lng) {
		return googleDistanceGetByHttp(prev_lat, prev_lng, getDoubleFrom(order.get("lat")), getDoubleFrom(order.get("lng"))) // travel time
				+ iOrderServingTime(order); // servicing time

	}

	// estimate the servicing duration of an order in a cluster
	static long avgSecondsInClusterByType_w_relaxation(HashMap<String,Object> order) {
		long type_seconds = getLongTimeFrom(order,"target_time_end")-getLongTimeFrom(order,"target_time_start");
		if (type_seconds <= 3600) // 1-hour order
			return iOrderServingTime(order);
		else if (type_seconds <= 3*3600) // up to 3-hour order
			return iOrderServingTime(order) - 10*60;	// relax 10 minutes
		else // longer (5-hour) order
			return iOrderServingTime(order) - 15*60; // relax 15 minutes
	}

	// determine where an order can be added to a cluster ("courier" is assigned to this cluster)
	static boolean bClusterSizeFitNew(List<HashMap<String,Object>> cluster, HashMap<String,Object> candidate_order, HashMap<String, Object> courier){
		// cluster's iterator
		Iterator<HashMap<String, Object>> it = cluster.iterator();
		// get base order
		HashMap<String, Object> base_order = it.next();
		// initialize the cluster_etf by that of the base order
		Long cluster_etf = getLongFrom(base_order.get("etf"));
		// compute total servicing duration and last deadline for the orders in the cluster
		while (it.hasNext()) {
			// get an order from cluster
			HashMap<String, Object> order = it.next();
			// add its servicing time to cluster_etf
			cluster_etf += avgSecondsInClusterByType_w_relaxation(order);
		}

		// add the servicing time of the candidate order to cluster_etf
		cluster_etf += avgSecondsInClusterByType_w_relaxation(candidate_order);
		
		// obtain the deadline of the candidate order
		long candidate_deadline = getLongTimeFrom(candidate_order,"target_time_end");

		// check if the candidate order can be added while still meeting the deadline
		if (cluster_etf  <= candidate_deadline)	
			return true;
		else
			return false;
	}

	/* Sort all incomplete orders like: "service" < "enroute" < "unassigned"
	 * In each category, earlier deadlines < later deadlines
	 * Orders with other status are removed.
	 */
	@SuppressWarnings("unchecked")
	static List<HashMap<String,Object>> sortIncompleteOrders(HashMap<String,Object> orders, HashMap<String, Object> couriers, Long currTime, LinkedHashMap<String, Object> outHashMap) {

		// initialize unassigned_order_list
		List<HashMap<String, Object>> incomplete_order_list = new ArrayList<>();
		for(Object order: orders.values()){
			incomplete_order_list.add((HashMap<String, Object>)order);
		}
		
		// for every unassigned order, add the field "tag" ("late", "urgent", "normal")
		for(HashMap<String, Object> order: incomplete_order_list){
			if ((order.get("status")).equals("unassigned"))
				tagUnassignedOrder(order, couriers, currTime);
			else
				order.put("tag", null);
		}
		
		// score unassigned orders according to their tags
		for(HashMap<String, Object> order: incomplete_order_list){
			if ((order.get("status")).equals("unassigned"))
				scoreUnassignedOrder(order, couriers, currTime);
		}

		// sort orders according to status, tag and urgency/score 
		Collections.sort(incomplete_order_list, new Comparator<HashMap<String, Object>>() {
			public int compare(HashMap<String, Object> o1, HashMap<String, Object> o2) {
				String o1_status = (String)o1.get("status");
				String o2_status = (String)o2.get("status");
				if (!o1_status.equals("servicing") && o2_status.equals("servicing"))
					// give priority to "servicing" over "enroute" ,"accepted", "assigned" and "unassigned"
					return 1;
				else if ((o1_status.equals("unassigned") || o1_status.equals("assigned") || o1_status.equals("accepted")) && o2_status.equals("enroute"))
					// give priority to "enroute" over "unassigned", "assigned" and "accepted"
					return 1;
				else if ((o1_status.equals("unassigned") || o1_status.equals("assigned")) && o2_status.equals("accepted"))
					// give priority to "accepted" over "unassigned" and "assigned"
					return 1;
				else if (o1_status.equals("unassigned") && o2_status.equals("assigned"))
					// give priority to "assigned" over "unassigned"
					return 1;
				else if (o1_status.equals("unassigned") && o2_status.equals("unassigned")){
					// both orders are unassigned, then check tag
					String o1_tag = (String)o1.get("tag");
					String o2_tag = (String)o2.get("tag");
					if(!o1_tag.equals("late") && o2_tag.equals("late"))
						// give priority to "late" over "urgent" and "normal"
						return 1;
					else if(o1_tag.equals("normal") && o2_tag.equals("urgent"))
						// give priority to "urgent" over "normal"
						return 1;
					else if(o1_tag.equals(o2_tag) && getDoubleFrom(o1.get("score")) > getDoubleFrom(o2.get("score")))
						// when they have the same tag, give priority to lower score
						return 1;
					else 
						return -1;
				}
				else if (o1_status.equals(o2_status) && !o1_status.equals("unassigned") && o1.containsKey("etf") && o2.containsKey("etf")) {
					// both orders have the same status, either "assigned", "accepted", "enroute", or "servicing", and both have "etf"
					if(getLongFrom(o1.get("etf")) > getLongFrom(o2.get("etf")))
						// give priority to earlier etf
						return 1;
					else 
						return -1;
				}
				else 
					return -1;
			}
			// returning 0 would merge keys
		});
		// return the sorted incomplete_order_list
		return incomplete_order_list;
	}

	@SuppressWarnings("unchecked")
	static void tagUnassignedOrder(HashMap<String, Object> order, HashMap<String, Object> couriers, Long currTime){
		// get order's location
		Double order_lat = getDoubleFrom(order.get("lat"));
		Double order_lng = getDoubleFrom(order.get("lng"));
		// initialize min score/ratio
		double min_score = Double.MAX_VALUE; // for normal orders
		double min_urgency_ratio = Double.MAX_VALUE;  // for late/urgent orders
		// get service time
		long service_duration = iOrderServingTime(order);
		// get deadline
		long order_deadline = getLongTimeFrom(order,"target_time_end");
		// go over all couriers to determine the min_urgency_ratio and min_nonurgency_ratio
		for(String courier_key: couriers.keySet()){
			HashMap<String, Object> courier = (HashMap<String, Object>) couriers.get(courier_key);
			// do zone-check and calculate the distance from courier's finish position to order 
			if(bOrderCanBeServedByCourier(order, courier)){
				// get courier's finish position
				Double courier_lat = getDoubleFrom(courier.get("finish_lat"));
				Double courier_lng = getDoubleFrom(courier.get("finish_lng"));
				long travel_duration = googleDistanceGetByHttp(courier_lat, courier_lng, order_lat, order_lng);
				// get courier's finish time
				long finish_time = getLongFrom(courier.get("finish_time"));
				// if this courier's finish time is earlier than the order's deadline 
				if(finish_time < order_deadline){
					double urgency_ratio = ((double)(travel_duration + service_duration)) / ((double)(order_deadline - finish_time));
					double score = travel_time_factor * (double)travel_duration + (double)(order_deadline - currTime);
					// update for minimum values
					if(urgency_ratio < min_urgency_ratio)
						min_urgency_ratio = urgency_ratio;
					if(score < min_score)
						min_score = score;
				}
			}
		}

		/* tag order with "late", "urgent" or "normal" */
		if(min_urgency_ratio > 1)
		{
			order.put("tag", "late");
			appendOrderInfo(order, "Late.");
		}
		else if(min_urgency_ratio >= urgencyThreshold || order_deadline - currTime <= minsUrgencyThreshold * 60) {
			order.put("tag", "urgent");
			order.put("min_urgency_ratio", min_urgency_ratio);
			appendOrderInfo(order, "Urgent.");
		}
		else {
			order.put("tag", "normal");
			order.put("min_score", min_score);
		}
	}	

	// for each unassigned order return a score, which is computed separately for late, urgent, normal orders
	static void scoreUnassignedOrder(HashMap<String, Object> order, HashMap<String, Object> couriers, Long currTime){
		double score = 0.;
		// get order's tag
		String tag = (String)order.get("tag");

		// compute scores separately for late, urgent, normal orders
		if(tag.equals("late"))	// "late" order
			score = getLongTimeFrom(order,"target_time_end").doubleValue();
		else if(tag.equals("urgent")){ 		// "urgent" order
			score = 1/(getDoubleFrom(order.get("min_urgency_ratio"))); // lower ratio go earlier
		}
		else if(tag.equals("normal")){	// "normal" order
			score = getDoubleFrom(order.get("min_score"));
		}

		order.put("score", score);
		return;
	}

	/* given a hashmap and a key for a time entry, return the Unix time (Long) */ 
	static Long getLongTimeFrom(HashMap<String,Object> hmap, String key) {
		Object val = (Object) hmap.get(key);
		
		if (val instanceof Long) {
			return (Long) val;
		} else if (val instanceof Integer) {
			return ((Integer) val).longValue();
		} else if (val instanceof String) {
			return SimpleDateFormatToUnixTime((String) val);
		} else {
			throw new IllegalArgumentException();
		}
	}

	/* given a Unix time (Long), return an objective according to human_time_format */ 
	static Object getTimeOutputInRightFormat(Long unixTime) {
		if (human_time_format) {
			return ((Object) UnixTimeToSimpleDateFormatNoDate(unixTime));
		}
		else {
			return ((Object) unixTime);
		}
	}
	
	/* given a Unix time (Long), return an objective according to human_time_format */ 
	static Object getTimeOutputInRightFormat(Long unixTime, Boolean hasDate) {
		if (human_time_format) {
			if (hasDate)
				return ((Object) UnixTimeToSimpleDateFormat(unixTime));
			else
				return ((Object) UnixTimeToSimpleDateFormatNoDate(unixTime));
		}
		else {
			return ((Object) unixTime);
		}
	}

	/* test whether two orders are assigned to different couriers 
	 * true if both have but have different couriers
	 * false if (i) both have no courier, or (ii) only one has a courier, or (iii) both have the same courier */
	static boolean bAssignedToDifferentCouriers(HashMap<String,Object> order1, HashMap<String,Object> order2) {
		// get their assigned couriers, possibly null
		String o1courier = (String) order1.get("courier_id");
		String o2courier = (String) order2.get("courier_id");

		// Perform the test
		if (o1courier != null && o1courier.equals(o2courier))
			return true;
		else
			// case: both==null or they are different or one has assigned the other = null.
			return false;
	}

	// if cross-zone servicing is allowed, compute its penalty
	static long computeCrossZonePenalty(HashMap<String,Object> order, HashMap<String,Object> courier, HashMap<String,Object> orders, HashMap<String,Object> couriers) {
		return 0; // this function is not implemented yet
	}

	@SuppressWarnings("unchecked")
	static public String printInput(HashMap<String,Object> input) {
		// --- read data from input to structures that are easy to use ---
		boolean json_input = getBooleanFrom(input.get("json_input"));
		boolean human_time_format = getBooleanFrom(input.get("human_time_format"));
		// read orders hashmap
		HashMap<String, Object> orders = (HashMap<String, Object>) input.get("orders");
		long nOrders = orders.size();
		// read couriers hashmap
		HashMap<String, Object> couriers = (HashMap<String, Object>) input.get("couriers");
		long nCouriers = couriers.size();

		// list keys in input
		System.out.println("Keys in the input: ");
		for(String key: input.keySet()) {
			System.out.print(key + "; ");
		}
		// print an empty line
		System.out.println();

		// print couriers
		System.out.println("# of couriers: " + nCouriers);
		System.out.println();

		// for each courier
		for(String courier_key: couriers.keySet()) {
			// print courier ID
			System.out.println("  courier: " + courier_key);
			// get the order by ID (key)
			HashMap<String, Object> courier = (HashMap<String, Object>) couriers.get(courier_key);
			/*// list the keys of each courier_key
			System.out.println("  keys in this couriers: ");
			for(String key: courier.keySet()) {
				System.out.print(key + "; ");
			}
			System.out.println();*/
			// print courier content manually
			System.out.println("    lat: " + getDoubleFrom(courier.get("lat")));
			System.out.println("    lng: " + getDoubleFrom(courier.get("lng")));
			System.out.println("    connected: " + getBooleanFrom(courier.get("connected")));
			//			System.out.println("    last_ping: " + (Integer) courier.get("last_ping"));

			// print zones
			System.out.print("    zones: " );
			assertListInteger(courier.get("zones"));
			List<Integer> zones = (List<Integer>) courier.get("zones");
			for(Integer zone: zones) {
				System.out.print(zone + " ");
			}
			System.out.println();

			// print assigned_orders
			System.out.print("    assigned_orders:" );
			List<String> assigned_orders = (List<String>) courier.get("assigned_orders");
			for(String assigned_order: assigned_orders) {
				System.out.print(" " + assigned_order);
			}

			// print an empty line
			System.out.println();
			System.out.println();
		}

		// print orders
		System.out.println(input.get("orders").getClass());
		System.out.println("# orders: " + nOrders);

		// print an empty line
		System.out.println();

		// for each order
		for(String order_key: orders.keySet()) {
			// print order ID
			System.out.println("  order: " + order_key);
			// get the order by ID (key)
			HashMap<String, Object> order = (HashMap<String, Object>) orders.get(order_key);
			// list the keys of each order
			/*System.out.println("  keys in this order: ");
			for(String key: order.keySet()) {
				System.out.print(key + "; ");
			}
			System.out.println();*/
			// print order content manually
			System.out.println("    id:  " + (String) order.get("id"));
			System.out.println("    status: " + (String) order.get("status"));
			System.out.println("    gas_type: " + (String) order.get("gas_type"));
			System.out.println("    gallons: " + getDoubleFrom(order.get("gallons")));
			System.out.println("    lat: " + getDoubleFrom(order.get("lat")));
			System.out.println("    lng: " + getDoubleFrom(order.get("lng")));
			if (human_time_format) {
				System.out.println("    target_time_start: " + (String) order.get("target_time_start"));
				System.out.println("    target_time_end : " + (String) order.get("target_time_end"));
			}
			else {
				System.out.println("    target_time_start: " + UnixTimeToSimpleDateFormat(getLongFrom(order.get("target_time_start"))));
				System.out.println("    target_time_end : " + UnixTimeToSimpleDateFormat(getLongFrom(order.get("target_time_end"))));
			}
			System.out.println("    zone_id: " + getIntegerFrom(order.get("zone_id")));
            //print zones
			System.out.print("    zones: " );
			assertListInteger(order.get("zones"));
			List<Integer> orderZones = (List<Integer>) order.get("zones");
			for(Integer orderZone: orderZones) {
				System.out.print(orderZone + " ");
			}
			System.out.println();

			// print the status time history
			if (json_input) {
				// use Integer format
				HashMap<String,Object> status_times = (HashMap<String,Object>) order.get("status_times");
				System.out.print("       ");
				for(String timekey: status_times.keySet()) {
					if (human_time_format) {
						System.out.print(timekey + ": " + SimpleDateFormatRemoveDate((String) status_times.get(timekey)) +"; ");
					}
					else {
						System.out.print(timekey + ": " + UnixTimeToSimpleDateFormatNoDate(getLongFrom(status_times.get(timekey))) +"; ");
					}
				}
				if (status_times.isEmpty())
					// print an empty line
					System.out.println();
				else {
					System.out.println();
					System.out.println();
				}
			}
			else { // use Long format
				HashMap<String,Long> status_times = (HashMap<String,Long>) order.get("status_times");
				System.out.print("       ");
				for(String timekey: status_times.keySet()) {
					System.out.print(timekey + ": " + getLongFrom(status_times.get(timekey)) +"; ");
				}
				if (status_times.isEmpty())
					// print an empty line
					System.out.println();
				else {
					System.out.println();
					System.out.println();
				}
			}
		}

		return("OK");
	}

	// check whether an order is found in the list of zones of a valid courier
	@SuppressWarnings("unchecked")
	static boolean bOrderCanBeServedByCourier(HashMap<String,Object> order, HashMap<String,Object> courier) {
		// at first the courier should be valid
		if(!getBooleanFrom(courier.get("valid"))) {
			return false;
        } else { // meanwhile the order should at the courier's zone
            assertListInteger(courier.get("zones"));
			List<Integer> courier_zones = (List<Integer>) courier.get("zones");
			
			assertListInteger(order.get("zones"));
			List<Integer> zones = (List<Integer>) order.get("zones");

			for (Integer zone: zones) {
                if (courier_zones.contains(zone)) {
                    return true;
                }
			}
            return false;
		}
	}

	// check whether a given finish_time no later than the order deadline 
	static boolean bOnTimeFinish(HashMap<String,Object>order, long finish_time) {
		long deadline = getLongTimeFrom(order,"target_time_end");
		if (finish_time <= deadline)
			return true;
		else
			return false;
	}	

	// compare two double numbers
	static boolean nearlyEqual(double a, double b, double epsilon) {
	    final double absA = Math.abs(a);
	    final double absB = Math.abs(b);
	    final double diff = Math.abs(a - b);

	    if (a == b) { // shortcut, handles infinities
	        return true;
	    } else if (a == 0 || b == 0 || diff < Float.MIN_NORMAL) {
	        // a or b is zero or both are extremely close to it
	        // relative error is less meaningful here
	        return diff < (epsilon * Float.MIN_NORMAL);
	    } else { // use relative error
	        return diff / (absA + absB) < epsilon;
	    }
	}
	
	/* given an object, return a Long object */ 
	static Long getLongFrom(Object obj) {
		if (obj instanceof Long) {
			return (Long) obj;
		} else if (obj instanceof Integer) {
			return ((Integer) obj).longValue();
		} else if (obj instanceof Double) {
			return (long) Math.abs((Double) obj);
		} else {
			throw new IllegalArgumentException();
		}
	}
	
	/* given an object, return an Integer object */ 
	static Integer getIntegerFrom(Object obj) {
		if (obj instanceof Integer) {
			return (Integer) obj;
		} else if (obj instanceof Long) {
			return ((Long) obj).intValue();
		} else if (obj instanceof Double) {
			return (int) Math.abs((Double) obj);
		} else {
			throw new IllegalArgumentException();
		}
	}
	
	/* given an object, return a Double object */ 
	static Double getDoubleFrom(Object obj) {
		if (obj instanceof Double) {
			return (Double) obj;
		} else if (obj instanceof Long) {
			return ((Long) obj).doubleValue();
		} else if (obj instanceof Integer) {
			return ((Integer) obj).doubleValue();
		} else {
			throw new IllegalArgumentException();
		}
	}
	
	/* given an object, return a Boolean object (true if obj==true or obj~=0) */
	static Boolean getBooleanFrom(Object obj) {
		if (obj instanceof Boolean) {
			return (Boolean) obj;
		} else if (obj instanceof Long) {
			return !((Long) obj).equals(0);
		} else if (obj instanceof Integer) {
			return !((Long) obj).equals(0);
		} else {
			throw new IllegalArgumentException();
		}
	}
	
	/* throw an exception if the input is not List<Integer>; no exception if the list is empty */
	static void assertListInteger(Object obj){
		if (!(obj instanceof List<?>)) {
			throw new IllegalArgumentException();
		} else if((!((List<?>) obj).isEmpty())) {
			if (!(((List<?>) obj).get(0) instanceof Integer))
				throw new IllegalArgumentException();
		}
	}
	
	/* given an order (object), append string to its "info" field */
	static void appendOrderInfo(HashMap<String,Object>order, String str){
		((StringBuilder) order.get("info")).append(" "+str);
	}
	
}
