package purpleJSONTest;

import java.io.File;
import java.io.IOException;
import java.util.*;

/* used for json to HashMap */
import org.codehaus.jackson.map.*;
import purpleOpt.PurpleOpt;

public class purpleJSONTest {

	@SuppressWarnings("unchecked")
	public static void main(String[] args){
		/* input file */
		String inputFileName = "test_20160225.json";
        File jsonFile = new File(inputFileName);
        
        /* use the next line if we want to reuse mapper */
        /* ObjectMapper mapper = new ObjectMapper(); */
       
        HashMap<String, Object> hmap = null;
        try {
        	/* load json file into a HashMap */
        	hmap = new ObjectMapper().readValue(jsonFile, HashMap.class);
        	System.out.println(hmap);
        	/* call PurpleOpt.printInput to print the input */
        	// String result = PurpleOpt.printInput(hmap);

        	// testZoneChecking(hmap);
        	
        	testComputeSuggestion(hmap);
        	
        	//testcomputeDistance(hmap);
        	
        	/* the example below is from http://wiki.fasterxml.com/JacksonInFiveMinutes */
        	/* 
        	Map<String,Object> userData = mapper.readValue(new File("user.json"), Map.class);
        	System.out.println(userData);
        	*/
        } catch (Exception e) {
        	e.printStackTrace();
        }

        return;
	}
	
	/* test PurpleOpt.bOrderCanBeServedByCourier for zone check*/
	/* disabled because PurpleOpt.bOrderCanBeServedByCourier is no longer public
	@SuppressWarnings("unchecked")
	static void testZoneChecking(HashMap<String,Object> hmap) {
		// get all the orders
		HashMap<String, Object> orders = (HashMap<String, Object>) hmap.get("orders");
		
		// get an order
		String order_key = "O0";
		HashMap<String, Object> order = (HashMap<String, Object>) orders.get(order_key);
				
		// get all the couriers
		HashMap<String, Object> couriers = (HashMap<String, Object>) hmap.get("couriers");
		
		for(String courier_key: couriers.keySet()) {
    		// get the courier
    		HashMap<String,Object> courier = (HashMap<String,Object>) couriers.get(courier_key);
    		// check zone
    		if (PurpleOpt.bOrderCanBeServedByCourier(order, courier)) 
    			System.out.println("Order " + order_key + " is in the list of zones of courier " + courier_key);
    		else
    			System.out.println("Order " + order_key + " is NOT in the list of zones of courier " + courier_key);
		}
		
		return;
	}
	*/
	
	/* call PurpleOpt.computeSuggestion for suggestion */
	static void testComputeSuggestion(HashMap<String,Object> hmap) {
		try {

			HashMap<String, Object> result = PurpleOpt.computeSuggestion(hmap);
			System.out.println(result);
			new ObjectMapper().writeValue(new File("suggestion.json"), result); // writing suggestion to the json file
		}
		catch (IOException e) {
			e.printStackTrace();
		}

		return;
	}
	
	/* call PurpleOpt.computeDistance for ETAs */
	static void testcomputeDistance(HashMap<String,Object> hmap) {
		try {
			HashMap<String,Object> result = PurpleOpt.computeDistance(hmap);
        	System.out.println(result);
        	new ObjectMapper().writeValue(new File("ETAs.json"), result); // writing suggestion to the json file        	
		}
		catch (IOException e) {
        	e.printStackTrace();
        }
		
		return;
	}

}
