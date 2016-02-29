package io.kuzzle.demo.demo_android;

import android.app.NotificationManager;
import android.location.Location;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kuzzle.demo.demo_android.enums.RideAction;
import io.kuzzle.demo.demo_android.enums.Status;
import io.kuzzle.demo.demo_android.enums.UserType;
import io.kuzzle.sdk.core.Kuzzle;
import io.kuzzle.sdk.core.KuzzleDataCollection;
import io.kuzzle.sdk.core.KuzzleDataMapping;
import io.kuzzle.sdk.core.KuzzleDocument;
import io.kuzzle.sdk.core.KuzzleOptions;
import io.kuzzle.sdk.core.KuzzleRoom;
import io.kuzzle.sdk.core.KuzzleRoomOptions;
import io.kuzzle.sdk.enums.Mode;
import io.kuzzle.sdk.enums.Scope;
import io.kuzzle.sdk.enums.Users;
import io.kuzzle.sdk.listeners.KuzzleResponseListener;
import io.kuzzle.sdk.listeners.OnQueryDoneListener;
import io.kuzzle.sdk.responses.KuzzleNotificationResponse;
import io.kuzzle.sdk.responses.KuzzleDocumentList;

public class MapActivity extends FragmentActivity implements OnMapReadyCallback {

  private Kuzzle kuzzle;
  private Handler handler = new Handler();
  private SupportMapFragment mapFragment;

  private String  kuzzle_host;
  private UserType userType;

  // Documents
  private KuzzleDocument self;
  private KuzzleDocument currentRide;

  // Collections
  private KuzzleDataCollection userCollection;
  private KuzzleDataCollection rideCollection;

  // Rooms
  private KuzzleRoom userRoom;
  private KuzzleRoom rideRoom;
  private KuzzleRoom usersScope;

  private JSONObject userSubscribeFilter;

  private Map<String, KuzzleDocument> userList = new HashMap<String, KuzzleDocument>();
  private Circle vicinity;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.map_view);
    userType = (UserType) getIntent().getExtras().get("type");
    kuzzle_host = getIntent().getExtras().get("kuzzle_host").toString();
    // Set listener of buttons
    findViewById(R.id.declineride).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        findViewById(R.id.notification).setVisibility(View.GONE);
        MapController.getSingleton(MapActivity.this).makeMarkerStopBlinking();
        try {
          manageRideProposal(RideAction.DECLINED);
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    });
    findViewById(R.id.acceptride).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        try {
          self.setContent("status", Status.RIDING);
          manageRideProposal(RideAction.ACCEPTED);
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    });
    findViewById(R.id.finishride).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        try {
          manageRideProposal(RideAction.FINISHED);
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    });

    // Set text on availability button according to type
    if (userType == UserType.CAB) {
      ((Button) findViewById(R.id.availability)).setText(getResources().getString(R.string.look_for_customers));
    } else {
      ((Button) findViewById(R.id.availability)).setText(getResources().getString(R.string.look_for_cab));
    }
    findViewById(R.id.availability).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        try {
          changeUserStatus();
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    });
    MapController mapControllerInstance = MapController.getSingleton(this);
    mapControllerInstance.setFinishRideHeader(findViewById(R.id.inridepanel));
    mapControllerInstance.setNotificationHeader(findViewById(R.id.notification));
    mapControllerInstance.setNotificationHeaderText((TextView) findViewById(R.id.notification_text));

    // Show map
    this.mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
    mapFragment.getMapAsync(this);
  }

  private void changeUserStatus() throws JSONException {
    // Change the attribute 'status' of the self document, other subscribed users will be notified
    Status userStatus = Status.valueOf(self.getContent("status").toString().toUpperCase());
    if (userType == UserType.CAB) {
      if (userStatus == Status.IDLE) {
        self.setContent("status", Status.TOHIRE.toString());
        ((Button) findViewById(R.id.availability)).setText(R.string.stop_looking_for_customers);
      } else if (userStatus == Status.TOHIRE) {
        self.setContent("status", Status.IDLE.toString());
        ((Button) findViewById(R.id.availability)).setText(R.string.look_for_customers);
      }
    } else if (userType == UserType.CUSTOMER) {
      if (userStatus == Status.IDLE) {
        self.setContent("status", Status.WANTTOHIRE.toString());
        ((Button) findViewById(R.id.availability)).setText(R.string.stop_looking_for_cab);
      } else if (userStatus == Status.WANTTOHIRE) {
        self.setContent("status", Status.IDLE.toString());
        ((Button) findViewById(R.id.availability)).setText(R.string.look_for_cab);
      }
    }
    self.save();
  }

  public void manageRideProposal(final RideAction action) throws JSONException {
    if (currentRide == null)
      return;
    if (!currentRide.getContent().getJSONObject("body").keys().hasNext() && !currentRide.getContent().isNull("_source")) {
      currentRide.getContent().put("body", currentRide.getContent().getJSONObject("_source"));
      currentRide.getContent().remove("_source");
    }
    currentRide.setContent("status", action.toString().toLowerCase());
    currentRide.save(new KuzzleResponseListener<KuzzleDocument>() {
      @Override
      public void onSuccess(KuzzleDocument document) {
        switch (action) {
          case DECLINED:
            // We decline the proposal
            currentRide.delete();
            break;
          case ACCEPTED:
            // We accept the proposal
            MapController.getSingleton(MapActivity.this.getBaseContext()).onRideAccepted();
            handler.post(new Runnable() {
              @Override
              public void run() {
                findViewById(R.id.gif).setVisibility(View.VISIBLE);
                findViewById(R.id.availability).setVisibility(View.INVISIBLE);
                ((WebView) findViewById(R.id.gif)).loadUrl("file:///android_asset/gif.html");
              }
            });
            break;
          case FINISHED:
            // We end the ride
            try {
              MapController.getSingleton(MapActivity.this.getBaseContext()).onRideFinished(currentRide.getContent("from").toString());
            } catch (JSONException e) {
              e.printStackTrace();
            }
            handler.post(new Runnable() {
              @Override
              public void run() {
                findViewById(R.id.gif).setVisibility(View.GONE);
                findViewById(R.id.availability).setVisibility(View.VISIBLE);
              }
            });
            currentRide.delete();
            try {
              self.setContent("status", Status.IDLE);
              handler.post(new Runnable() {
                @Override
                public void run() {
                  try {
                    changeUserStatus();
                  } catch (JSONException e) {
                    e.printStackTrace();
                  }
                }
              });
            } catch (JSONException e) {
              e.printStackTrace();
            }
            break;
        }
      }

      @Override
      public void onError(JSONObject error) {
        Log.e("cabble", error.toString());
      }
    });
  }

  private void manageResponseProposal(RideAction action, KuzzleDocument proposal) throws JSONException {
    switch (action) {
      case DECLINED:
        // Candidate declined the proposal
        MapController.getSingleton(MapActivity.this.getBaseContext()).onRideRefused(proposal.getContent("to").toString());
        currentRide.delete();
        handler.post(new Runnable() {
          @Override
          public void run() {
            Toast.makeText(MapActivity.this, "Proposal declined", Toast.LENGTH_LONG).show();
          }
        });
        break;
      case ACCEPTED:
        // Candidate accepted our proposal
        MapController.getSingleton(MapActivity.this.getBaseContext()).onRideAccepted();
        handler.post(new Runnable() {
          @Override
          public void run() {
            findViewById(R.id.gif).setVisibility(View.VISIBLE);
            findViewById(R.id.availability).setVisibility(View.INVISIBLE);
            ((WebView) findViewById(R.id.gif)).loadUrl("file:///android_asset/gif.html");
          }
        });
        break;
      case FINISHED:
        // Candidate ended the ride
        MapController.getSingleton(MapActivity.this.getBaseContext()).onRideFinished(proposal.getContent("from").toString());
        handler.post(new Runnable() {
          @Override
          public void run() {
            findViewById(R.id.gif).setVisibility(View.GONE);
            findViewById(R.id.availability).setVisibility(View.VISIBLE);
          }
        });
        currentRide.delete();
        self.setContent("status", Status.IDLE);
        break;
      case CANCELLED:
        // Candidate refused our proposal
        MapController.getSingleton(MapActivity.this.getBaseContext()).onRideCancelled(proposal.getContent("from").toString());
        currentRide.delete();
        handler.post(new Runnable() {
          @Override
          public void run() {
            MapActivity.this.findViewById(R.id.notification).setVisibility(View.GONE);
          }
        });
        break;
      }
  }


  private void  publishRideProposal(RideAction action, String candidateId) throws JSONException {
    switch (action) {
      case ASK:
      case PROPOSE:
        currentRide.setContent("from", self.getId());
        currentRide.setContent("to", candidateId);
        currentRide.setContent("status", "awaiting");
        currentRide.save();
        break;
    }
  }

  private void connectToKuzzle() throws URISyntaxException {
    KuzzleOptions options = new KuzzleOptions();
    // We want our app to auto reconnect in case of lost of wifi / 3G
    options.setAutoReconnect(true);
    // We want our app to auto replay all the actions stacked during offline
    options.setAutoReplay(true);
    options.setQueuable(true);
    options.setOfflineMode(Mode.AUTO);
    options.setDefaultIndex("cabble");
    // Connect to kuzzle using 'cabble' index
    kuzzle = new Kuzzle(kuzzle_host, options, new KuzzleResponseListener<Void>() {
      @Override
      public void onSuccess(Void object) {
        Log.i("cabble", "Connected to kuzzle");
        prepareMapping(new KuzzleResponseListener<KuzzleDataMapping>() {
          @Override
          public void onSuccess(KuzzleDataMapping mapping) {
            createMySelf();
          }

          @Override
          public void onError(JSONObject error) {
            Log.e("cabble", "An error occured during the connection to kuzzle: " + error.toString());
          }
        });
      }

      @Override
      public void onError(JSONObject error) {
        Log.e("cabble", "Error during connection: " + error.toString());
      }
    });
  }

  private void mapGeoPoint() {
    // Make the geo spatial mapping
    KuzzleDataMapping mapping = new KuzzleDataMapping(userCollection);
    try {
      mapping.set("pos", new JSONObject().put("type", "geo_point"));
      mapping.apply(new KuzzleResponseListener<KuzzleDataMapping>() {
        @Override
        public void onSuccess(KuzzleDataMapping response) {
          Log.e("map", response.toString());
        }

        @Override
        public void onError(JSONObject error) {

        }
      });
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  private void  prepareMapping(final KuzzleResponseListener<KuzzleDataMapping> listener) {
    kuzzle.listCollections(new KuzzleResponseListener<JSONObject>() {
      @Override
      public void onSuccess(JSONObject object) {
        // Create collections
        userCollection = kuzzle.dataCollectionFactory(getResources().getString(R.string.cabble_collection_users));
        rideCollection = kuzzle.dataCollectionFactory(getResources().getString(R.string.cabble_collection_rides));
        listener.onSuccess(null);
      }

      @Override
      public void onError(JSONObject error) {
        JSONObject query = new JSONObject();
        try {
          // Create index
          query.put("controller", "admin").put("action", "createIndex").put("index", "cabble");
          Kuzzle.QueryArgs args = new Kuzzle.QueryArgs();
          args.controller = "admin";
          args.action = "createIndex";
          kuzzle.query(args, query, new OnQueryDoneListener() {
            @Override
            public void onSuccess(JSONObject object) {
              userCollection = kuzzle.dataCollectionFactory(getResources().getString(R.string.cabble_collection_users));
              rideCollection = kuzzle.dataCollectionFactory(getResources().getString(R.string.cabble_collection_rides));
              // Create user collection
              userCollection.create(new KuzzleResponseListener<JSONObject>() {
                @Override
                public void onSuccess(JSONObject object) {
                  mapGeoPoint();
                  // Create ride collection
                  rideCollection.create(new KuzzleResponseListener<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject object) {
                      listener.onSuccess(null);
                    }

                    @Override
                    public void onError(JSONObject error) {
                      Log.e("cabble", "Error during creation of collection " + error.toString());
                    }
                  });
                }

                @Override
                public void onError(JSONObject error) {
                  Log.e("cabble", "Error during creation of collection " + error.toString());
                }
              });
            }

            @Override
            public void onError(JSONObject error) {
              Log.e("cabble", "Error during creation of index " + error.toString());
            }
          });
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    });
  }

  private void createMySelf() {
    // Creating the document that represents ourself
    JSONObject pos = null;
    try {
      pos = new JSONObject()
          .put("lat", getResources().getString(R.string.default_lat))
          .put("lon", getResources().getString(R.string.default_lon));
    } catch (JSONException e) {
      e.printStackTrace();
    }
    try {
      self = new KuzzleDocument(userCollection);
      self.setContent("type", userType.toString().toLowerCase())
          .setContent("pos", pos)
          .setContent("status", Status.IDLE.toString())
          .setContent("sibling", "none");
    } catch (JSONException e) {
      e.printStackTrace();
    }
    userCollection.createDocument(self, new KuzzleResponseListener<KuzzleDocument>() {
      @Override
      public void onSuccess(KuzzleDocument doc) {
        try {
          self.getContent().put("_id", doc.getId());
          initUserSubscribeFilter();
          subscribeToUsersScope();
          subscribeToCollections();
          getConnectedUsers();
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }

      @Override
      public void onError(JSONObject error) {
        Log.e("cabble", "Error during creation of self " + error.toString());
      }
    });
  }

  private JSONObject  getGeoDistanceFilter() throws JSONException {
    // Make the geo distance filter which will represent our vicinity (5km)
    JSONObject geo_distance = new JSONObject();
    JSONObject filter = new JSONObject();
    geo_distance.put("pos", self.getContent("pos"));
    geo_distance.put("distance", getResources().getString(R.string.distance_filter) + "m");
    filter.put("geo_distance", geo_distance);
    return filter;
  }

  private void initUserSubscribeFilter() throws JSONException {
    /* We want to subscribe to available customers and taxi if we are a taxi.
       We want to subscribe to available taxi if we are customers.
       Finally we want to subscribe to users in our vicinity
    */
    if (userSubscribeFilter == null) {
      userSubscribeFilter = new JSONObject();
      JSONArray and = new JSONArray();
      JSONArray types = new JSONArray();
      JSONArray status = new JSONArray();
      JSONObject statu = new JSONObject();
      JSONObject terms = new JSONObject();
      JSONObject terms2 = new JSONObject();
      JSONObject type = new JSONObject();
      types.put("cab");
      status.put("idle").put("toHire").put("riding");
      if (userType == UserType.CAB) {
        types.put("customer");
        status.put("wantToHire");
      } else {
        status.put("hired");
      }
      type.put("type", types);
      statu.put("status", status);
      terms.put("terms", statu);
      terms2.put("terms", type);
      and.put(terms).put(terms2);
      and.put(getGeoDistanceFilter());

      userSubscribeFilter.put("and", and);
    }
  }

  private JSONObject initRideSubscribeFilter() throws JSONException {
    JSONObject rideFilter = new JSONObject();
    JSONArray or = new JSONArray();
    JSONObject term = new JSONObject();
    JSONObject term2 = new JSONObject();
    JSONObject to = new JSONObject();
    JSONObject from = new JSONObject();
    to.put("to", self.getId());
    from.put("from", self.getId());
    term.put("term", to);
    term2.put("term", from);
    or.put(term).put(term2);
    rideFilter.put("or", or);
    return rideFilter;
  }

  private void subscribeToCollections() throws JSONException {
    KuzzleRoomOptions options = new KuzzleRoomOptions();
    options.setSubscribeToSelf(false);
    options.setScope(Scope.ALL);
    userRoom = userCollection.subscribe(userSubscribeFilter, options, new KuzzleResponseListener<KuzzleNotificationResponse>() {
      @Override
      public void onSuccess(KuzzleNotificationResponse notification) {
        if (notification.getDocument() != null)
          Log.e("cabble", "A User moved " + notification.getDocument().toString());
        else
          Log.e("cabble", "User out of scope " + notification.getController() + " " + notification.getAction() + " " + notification.getResult());
        if (notification.getScope() != Scope.OUT && !notification.getAction().equals("delete")) {
          if (!notification.getDocument().getId().equals(self.getId())) {
            KuzzleDocument doc = notification.getDocument();
            String userId = doc.getId();
            userList.put(userId, notification.getDocument());
            try {
              MapController.getSingleton(MapActivity.this).moveMarker(userId, UserType.valueOf(((String)doc.getContent("type")).toUpperCase()), ((JSONObject)doc.getContent("pos")).getDouble("lat"), ((JSONObject)doc.getContent("pos")).getDouble("lon"));
              if (Status.valueOf(doc.getContent("status").toString().toUpperCase()) == Status.TOHIRE ||
                  Status.valueOf(doc.getContent("status").toString().toUpperCase()) == Status.WANTTOHIRE) {
                MapController.getSingleton(MapActivity.this).makeMarkerBlink(doc.getId());
              } else {
                MapController.getSingleton(MapActivity.this).makeMarkerStopBlinking(doc.getId());
              }
            } catch (JSONException e) {
              e.printStackTrace();
            }
          }
        } else {
          // The user is out of our vicinity
          try {
            MapController.getSingleton(MapActivity.this.getBaseContext()).hideCandidate(notification.getResult().getString("_id"));
          } catch (JSONException e) {
            e.printStackTrace();
          }
        }
      }

      @Override
      public void onError(JSONObject error) {
        Log.e("cabble", "Error while subscribing to users " + error.toString());
      }
    });
    // Subscribe to rides
    rideRoom = rideCollection.subscribe(initRideSubscribeFilter(), options, new KuzzleResponseListener<KuzzleNotificationResponse>() {
      @Override
      public void onSuccess(KuzzleNotificationResponse notification) {
        if (!notification.getAction().equals("delete")) {
          currentRide = notification.getDocument();
          manageRideProposal();
        }
      }

      @Override
      public void onError(JSONObject error) {
        Log.e("cabble", "Error while subscribing to rides " + error.toString());
      }
    });
  }

  private void  pushNotification() {
    // Push an android notification when a proposal is received
    NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this)
              .setSmallIcon(R.drawable.favicon)
              .setContentText("Go to cabble to see.");
    if (userType == UserType.CUSTOMER)
      mBuilder.setContentTitle("A cab is ready to take you !");
    else
      mBuilder.setContentTitle("A customer wants a ride !");
    if (mBuilder != null) {
      mBuilder.setVibrate(new long[] {1000, 1000});
      Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
      mBuilder.setSound(alarmSound);
      NotificationManager mNotifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      mNotifyMgr.notify(42, mBuilder.build());
      mBuilder.build();
    }
  }

  private void manageRideProposal() {
    // Handle the proposal
    try {
      if (currentRide.getContent("from").equals(self.getId()) || !currentRide.getContent("status").equals("awaiting")) {
        handler.post(new Runnable() {
          @Override
          public void run() {
            try {
              manageResponseProposal(RideAction.valueOf(((String)currentRide.getContent("status")).toUpperCase()), currentRide);
            } catch (JSONException e) {
              e.printStackTrace();
            }
          }
        });
      } else {
        MapController.getSingleton(this).onRideProposal(currentRide, userType);
        pushNotification();
      }
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  private void subscribeToUsersScope() throws JSONException {
    // Subscription to know when a user is disconnected
    KuzzleRoomOptions options = new KuzzleRoomOptions();
    options.setUsers(Users.OUT);
    JSONObject meta = new JSONObject();
    meta.put("_id", self.getId());
    options.setMetadata(meta);
    options.setSubscribeToSelf(false);
    usersScope = userCollection.subscribe(null, options, new KuzzleResponseListener<KuzzleNotificationResponse>() {
      @Override
      public void onSuccess(KuzzleNotificationResponse notification) {
        try {
          if (notification.getAction() != null) {
            if (notification.getAction().equals("off")) {
              MapController.getSingleton(MapActivity.this.getBaseContext()).removeCandidate(notification.getMetadata().getString("_id"));
              userList.remove(notification.getMetadata().getString("_id"));
            }
          }
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }

      @Override
      public void onError(JSONObject error) {
        Log.e("cabble error", error.toString());
      }
    });
  }

  private void getConnectedUsers() throws JSONException {
    // Search for all users according to type, availability and vicinity
    JSONObject filter = new JSONObject();
    JSONArray types = new JSONArray();
    types.put("cab");
    if (userType == UserType.CAB) {
      types.put("customer");
    }
    initUserSubscribeFilter();
    filter.put("filter", userSubscribeFilter);
    filter.put("query", new JSONObject().put("terms", new JSONObject().put("type", types)));
    kuzzle.dataCollectionFactory(getResources().getString(R.string.cabble_collection_users)).advancedSearch(filter, new KuzzleResponseListener<KuzzleDocumentList>() {
      @Override
      public void onSuccess(KuzzleDocumentList response) {
        Log.e("cabble## ", response.toString());
        List<KuzzleDocument> hits = response.getDocuments();
        for (int i = 0; i < hits.size(); i++) {
          KuzzleDocument doc = hits.get(i);
          userList.put(doc.getId(), doc);
          updateUserPosition(hits.get(i));
        }
      }

      @Override
      public void onError(JSONObject error) {
        Log.e("cabble", error.toString());
      }
    });
  }

  private void updateUserPosition(final KuzzleDocument object) {
    // A user moved
    try {
      JSONObject source = object.getContent().getJSONObject("body");
      JSONObject pos = source.getJSONObject("pos");
      MapController.getSingleton(this).moveMarker(object.getContent().getString("_id"), UserType.valueOf(source.getString("type").toUpperCase()), pos.getDouble("lat"), pos.getDouble("lon"));
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

  private void updateMyPosition(LatLng location) throws JSONException {
    // We moved
    Log.e("cabble", "we moved " + location.toString());
    JSONObject pos = new JSONObject()
        .put("lat", location.latitude)
        .put("lon", location.longitude);
    if (self != null) {
      self.setContent("pos", pos);
      self.save();
      vicinity.setCenter(new LatLng(location.latitude, location.longitude));
    }
  }

  private void invalidate() throws JSONException {
    if (kuzzle != null) {
      if (self != null)
        kuzzle.dataCollectionFactory(getResources().getString(R.string.cabble_collection_users)).deleteDocument(self.getId());
      MapController.getSingleton(this).clearMap();
      if (userRoom != null)
        userRoom.unsubscribe();
      if (rideRoom != null)
        rideRoom.unsubscribe();
      if (usersScope != null)
        usersScope.unsubscribe();
      kuzzle.disconnect();
    }
  }

  @Override
  protected void onDestroy() {
    try {
      this.invalidate();
    } catch (JSONException e) {
      e.printStackTrace();
    }
    super.onDestroy();
  }

  @Override
  public void onMapReady(GoogleMap googleMap) {
    // Connection to kuzzle
    try {
      connectToKuzzle();
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }
    googleMap.setMyLocationEnabled(true);
    googleMap.setOnMyLocationChangeListener(new GoogleMap.OnMyLocationChangeListener() {
      @Override
      public void onMyLocationChange(Location location) {
        try {
          LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
          // Update our position
          updateMyPosition(latLng);
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
    });
    double lat = Double.parseDouble(MapActivity.this.getResources().getString(R.string.default_lat));
    double lon = Double.parseDouble(MapActivity.this.getResources().getString(R.string.default_lon));
    vicinity = googleMap.addCircle(new CircleOptions()
        .center(new LatLng(lat, lon))
        .radius(Double.parseDouble(MapActivity.this.getResources().getString(R.string.distance_filter))));

    MapController.getSingleton(this).setMapView(googleMap);
    MapController.getSingleton(this).setHandler(handler);
    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(Double.parseDouble(MapActivity.this.getResources().getString(R.string.default_lat)), Double.parseDouble(MapActivity.this.getResources().getString(R.string.default_lon))), 14));
    googleMap.getUiSettings().setZoomControlsEnabled(true);
    googleMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
      @Override
      public View getInfoWindow(Marker marker) {
        try {
          MapController.getSingleton(MapActivity.this).hasInfoWindow(MapActivity.this.userList.get(marker.getSnippet()).getContent().getString("_id"), true);
        } catch (JSONException e) {
          e.printStackTrace();
        }
        return null;
      }

      @Override
      public View getInfoContents(final Marker marker) {
        View v = null;
        String id = marker.getSnippet();
        try {
          if (Status.valueOf(userList.get(id).getContent("status").toString().toUpperCase()) == Status.IDLE ||
              UserType.valueOf(userList.get(id).getContent("type").toString().toUpperCase()) == UserType.valueOf(self.getContent("type").toString().toUpperCase())) {
            v = getLayoutInflater().inflate(R.layout.idle_bubble, null);
            ((TextView) v.findViewById(R.id.userid)).setText(MapActivity.this.userList.get(id).getContent().getString("_id"));
            ((TextView) v.findViewById(R.id.status)).setText(MapActivity.this.userList.get(id).getContent("status").toString());
          } else {
            if (userType != UserType.CAB) {
              v = getLayoutInflater().inflate(R.layout.taxi_bubble, null);
            } else {
              v = getLayoutInflater().inflate(R.layout.customer_bubble, null);
            }
          }
        } catch (JSONException e) {
          e.printStackTrace();
        }
        return v;
      }
    });
    googleMap.setOnInfoWindowClickListener(new GoogleMap.OnInfoWindowClickListener() {
      @Override
      public void onInfoWindowClick(Marker marker) {
        try {
          // Publish a proposal to a specific user
          currentRide = new KuzzleDocument(rideCollection);
          publishRideProposal(RideAction.ASK, marker.getSnippet());
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    });
    googleMap.setOnMapClickListener(new GoogleMap.OnMapClickListener() {
      @Override
      public void onMapClick(LatLng latLng) {
        MapController.getSingleton(MapActivity.this).onMapClick();
      }
    });
  }
}
