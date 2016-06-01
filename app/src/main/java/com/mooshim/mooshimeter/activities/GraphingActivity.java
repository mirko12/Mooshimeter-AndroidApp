package com.mooshim.mooshimeter.activities;

import android.app.ActionBar;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupWindow;

import com.mooshim.mooshimeter.R;
import com.mooshim.mooshimeter.common.CooldownTimer;
import com.mooshim.mooshimeter.interfaces.GraphingActivityInterface;
import com.mooshim.mooshimeter.common.MeterReading;
import com.mooshim.mooshimeter.interfaces.MooshimeterControlInterface;
import com.mooshim.mooshimeter.interfaces.MooshimeterDelegate;
import com.mooshim.mooshimeter.devices.MooshimeterDeviceBase;
import com.mooshim.mooshimeter.common.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import lecho.lib.hellocharts.computator.ChartComputator;
import lecho.lib.hellocharts.gesture.ContainerScrollType;
import lecho.lib.hellocharts.gesture.ZoomType;
import lecho.lib.hellocharts.model.Axis;
import lecho.lib.hellocharts.model.Line;
import lecho.lib.hellocharts.model.LineChartData;
import lecho.lib.hellocharts.model.PointValue;
import lecho.lib.hellocharts.model.Viewport;
import lecho.lib.hellocharts.renderer.AxesRenderer;
import lecho.lib.hellocharts.renderer.ChartRenderer;
import lecho.lib.hellocharts.view.LineChartView;

public class GraphingActivity extends MyActivity implements GraphingActivityInterface, MooshimeterDelegate {

    ///////////////////////
    // STATICS
    ///////////////////////

    private static final String TAG = GraphingActivity.class.getSimpleName();

    private static int[] mColorList = {
            0xFFFF0000, // R
            0xFF0000FF, // B
            0xFF00FF00, // G
            0xFF008888, // G+B
            0xFF880088, // R+B
            0xFF888800, // R+G
    };

    public enum ChDispModes {
        OFF,
        MANUAL,
        AUTO,
    }

    ///////////////////////
    // WIDGETS
    ///////////////////////

    public GraphSettingsView mSettingsView;
    public PopupWindow mSettingsWindow;
    public Button mConfigButton;

    LineChartView[] mChart={null,null};
    Axis xAxis, yAxisLeft, yAxisRight;

    private Timer refresh_timer = new Timer();

    ///////////////////////
    // BEHAVIOR CONTROL
    ///////////////////////

    final static int maxNumberOfPoints = 10000;
    int maxNumberOfPointsOnScreen = 32;
    ChDispModes[] dispModes = new ChDispModes[]{ChDispModes.AUTO, ChDispModes.AUTO};;
    protected boolean autoScrollOn = true;
    protected boolean xyModeOn = false;
    protected boolean bufferModeOn = false;
    protected boolean playing = true;

    private MooshimeterDeviceBase mMeter;
    private double time_start;

    ///////////////////////
    // HELPER VARS
    ///////////////////////

    //Create lists for storing actual value of points that are visible on the screen for both axises
    PointArrayWrapper[] axisValueHelpers={null,null};

    ///////////////////////
    // HELPER CLASSES
    ///////////////////////
    private class PointArrayWrapper {
        public List<PointValue> backing;
        private Viewport util_vp = new Viewport();
        public PointArrayWrapper() {
            this(new ArrayList<PointValue>());
        }
        public PointArrayWrapper(List<PointValue>to_wrap) {
            backing = to_wrap;
        }
        public PointArrayWrapper getValuesInViewport(Viewport v) {
            List<PointValue> rval = new ArrayList<>();
            for(PointValue p:backing) {
                if(         p.getX()>v.left
                        &&  p.getX()<v.right) {
                    rval.add(p);
                }
            }
            return new PointArrayWrapper(rval);
        }
        private void minMaxProcessPoint(PointValue p) {
            float x = p.getX();
            float y = p.getY();
            if(x> util_vp.right) {
                util_vp.right = x;
            }
            if(x< util_vp.left) {
                util_vp.left = x;
            }
            if(y> util_vp.top) {
                util_vp.top = y;
            }
            if(y< util_vp.bottom) {
                util_vp.bottom = y;
            }
        }
        public Viewport getBoundingViewport() {
            util_vp.left=Float.MAX_VALUE;
            util_vp.bottom=Float.MAX_VALUE;
            util_vp.right=-Float.MAX_VALUE;
            util_vp.top=-Float.MAX_VALUE;
            for(PointValue p:backing) {
                minMaxProcessPoint(p);
            }
            return util_vp;
        }
    }

    ///////////////////////
    // ACTIVITY OVERRIDES
    ///////////////////////

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graphing);
        mChart[0] = (LineChartView) findViewById(R.id.lineChart1);
        mChart[1] = (LineChartView) findViewById(R.id.lineChart2);
        for(int i = 0; i < 2; i++) {
            mChart[i].setViewportCalculationEnabled(false);
            mChart[i].setMaxZoom((float) 1e6);
            mChart[i].setInteractive(true);
            mChart[i].setZoomType(ZoomType.VERTICAL);
            mChart[i].setContainerScrollEnabled(true, ContainerScrollType.HORIZONTAL);
        }
        //mChart[1].setBackgroundColor(0x40FF0000);

        mConfigButton = (Button)findViewById(R.id.config_btn);

        Intent intent = getIntent();
        mMeter = (MooshimeterDeviceBase)getDeviceWithAddress(intent.getStringExtra("addr"));

        mSettingsView = new GraphSettingsView(this, this);
        mSettingsWindow = new PopupWindow(this);
        mSettingsWindow.setContentView(mSettingsView);
        mSettingsWindow.setOutsideTouchable(false);

        LineChartData lineChartData = new LineChartData(new ArrayList<Line>());
        xAxis = new Axis().setName("Time [s]").setHasLines(true);
        xAxis.setTextColor(0xFF000000);
        xAxis.setHasTiltedLabels(true);
        lineChartData.setAxisXBottom(xAxis);

        yAxisLeft = new Axis().setName(mMeter.getInputLabel(MooshimeterControlInterface.Channel.CH1)).setHasLines(true);
        yAxisLeft.setLineColor(mColorList[0]);
        yAxisLeft.setTextColor(mColorList[0]);
        yAxisLeft.setHasTiltedLabels(true);

        lineChartData.setAxisYLeft(yAxisLeft);
        mChart[0].setLineChartData(lineChartData);

        lineChartData = new LineChartData(new ArrayList<Line>());

        yAxisRight = new Axis().setName(mMeter.getInputLabel(MooshimeterControlInterface.Channel.CH2)).setHasLines(true);
        yAxisRight.setLineColor(mColorList[1]);
        yAxisRight.setTextColor(mColorList[1]);
        yAxisRight.setHasTiltedLabels(true);
        //yAxisRight.setFormatter(new RightAxisFormatter());
        lineChartData.setAxisYRight(yAxisRight);
        mChart[1].setLineChartData(lineChartData);

        //Create new line with some default values
        for(int i = 0; i < 2; i++) {
            Line line = new Line();
            line.setHasLines(true);
            line.setHasPoints(false);
            List<Line> lines = new ArrayList<>();
            lines.add(line);
            mChart[i].getLineChartData().setLines(lines);
            line.setColor(mColorList[i]);
        }

        axisValueHelpers[0]  = new PointArrayWrapper();
        axisValueHelpers[1] = new PointArrayWrapper();
        axisValueHelpers = new PointArrayWrapper[]{axisValueHelpers[0], axisValueHelpers[1]};

        time_start = Util.getNanoTime();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refresh_timer.cancel();
        Util.dispatch(new Runnable() {
            @Override
            public void run() {
                if(bufferModeOn) {
                    setBufferModeOn(false);
                }
                mMeter.pause();
                mMeter.removeDelegate();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if(mMeter==null) {
            onBackPressed();
        }

        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);
        ActionBar actionBar = getActionBar();
        actionBar.hide();

        final MooshimeterDelegate d = this;

        Util.dispatch(new Runnable() {
            @Override
            public void run() {
                mMeter.addDelegate(d);
                if(bufferModeOn) {
                    setBufferModeOn(false);
                }
                if(playing) {
                    mMeter.stream();
                    Log.i(TAG, "Stream requested");
                    refresh_timer.scheduleAtFixedRate(new TimerTask() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    },100,1000);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        transitionToActivity(mMeter, DeviceActivity.class);
    }

    /////////////////////////
    // Getters/Setters
    /////////////////////////

    public void setXyModeOn(boolean on) {
        this.xyModeOn = on;
        if(xyModeOn) {
            setXAxisTitle(mMeter.getInputLabel(MooshimeterControlInterface.Channel.CH1));
            setYAxisTitle(0, mMeter.getInputLabel(MooshimeterControlInterface.Channel.CH2));
            setYAxisTitle(1, "NA");
        } else {
            setXAxisTitle("Time [s]");
            setYAxisTitle(0, mMeter.getInputLabel(MooshimeterControlInterface.Channel.CH1));
            setYAxisTitle(1, mMeter.getInputLabel(MooshimeterControlInterface.Channel.CH2));
        }
    }

    public void setBufferModeOn(boolean on) {
        this.bufferModeOn = on;
        axisValueHelpers[0].backing.clear();
        axisValueHelpers[1].backing.clear();
        mMeter.setBufferMode(MooshimeterControlInterface.Channel.CH1,on);
        mMeter.setBufferMode(MooshimeterControlInterface.Channel.CH2,on);
    }

    public void setAutoScrollOn(boolean autoScrollOn) {
        this.autoScrollOn = autoScrollOn;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        if(playing) {
            mMeter.stream();
            if(!autoScrollOn && !bufferModeOn) {
                setAutoScrollOn(true);
            }
        } else {
            mMeter.pause();
            if(autoScrollOn && !bufferModeOn) {
                setAutoScrollOn(false);
            }
        }
    }

    /////////////////////////
    // Touch Dispatching
    /////////////////////////

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        //If it's on the left half of the screen, dispatch it to the left chart
        float xpos = event.getX();
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        if(xpos < size.x/2) {
            // LEFT SIDE TOUCH
            // Consume the touch (return true) and send it to mChart[0], which is behind mChart[1]
            mChart[0].dispatchTouchEvent(event);
            return true;
        } else {
            // RIGHT SIDE TOUCH
            // Don't consume the touch, let it be dispatched as normal.
            //mChart[1].dispatchTouchEvent(event);
            return super.dispatchTouchEvent(event);
        }
        //requestDisallowInterceptTouchEvent(mSomeViewGroup, true);
        //return super.dispatchTouchEvent(event);
    }

    /////////////////////////
    // Widget Handlers+Refreshers
    /////////////////////////

    public void onConfigButton(View v) {
        if(mSettingsWindow.isShowing()) {
            mSettingsWindow.dismiss();
        } else {
            View base = this.findViewById(android.R.id.content);
            mSettingsWindow.setFocusable(true);
            mSettingsWindow.setWindowLayoutMode(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            // Clear the default translucent background
            mSettingsWindow.setBackgroundDrawable(new BitmapDrawable());
            mSettingsWindow.showAtLocation(base, Gravity.CENTER,0,0);
            //mSettingsWindow.setWindowLayoutType();
            //mSettingsWindow.update(0,0,base.getWidth()-400,base.getHeight()-100);
        }
    }

    /////////////////////////
    // GraphingActivityInterface methods
    /////////////////////////

    private void setLineValues(int i,List<PointValue>data) {
        mChart[i].getLineChartData().getLines().get(0).setValues(data);
    }

    @Override
    public void refresh() {
        if(axisValueHelpers[0].backing.size()==0) {
            // There's no data, just return.
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                // If autoscroll is on, time drives the xrange
                // xrange drives what data is onscreen
                // if autorange is on, the data drives the Y bounds
                if(xyModeOn) {
                    // Just grab the latest N readings and autorange both axes
                    List<PointValue> l0 = axisValueHelpers[0].backing;
                    List<PointValue> l1 = axisValueHelpers[1].backing;
                    PointArrayWrapper xy = new PointArrayWrapper();
                    int max_i = l0.size()<l1.size()?l0.size():l1.size();
                    int min_i = max_i-maxNumberOfPointsOnScreen>=0?max_i-maxNumberOfPointsOnScreen:0;
                    for(int i = min_i; i < max_i; i++) {
                        xy.backing.add(new PointValue(l0.get(i).getY(),l1.get(i).getY()));
                    }
                    setLineValues(0,xy.backing);
                    // Right axis should show no values
                    setLineValues(1,new ArrayList<PointValue>());
                    Viewport fitsTheData = xy.getBoundingViewport();
                    mChart[0].setMaximumViewport(fitsTheData);
                    mChart[0].setCurrentViewport(fitsTheData);
                } else {
                    Viewport time_base_vp = new Viewport();
                    time_base_vp.right = axisValueHelpers[0].backing.get(axisValueHelpers[0].backing.size()-1).getX();
                    time_base_vp.left = time_base_vp.right - (((float)maxNumberOfPointsOnScreen*mMeter.getBufferDepth())/(float)mMeter.getSampleRateHz());
                    for(int i = 0; i < 2; i++) {
                        Viewport present_vp = mChart[i].getCurrentViewport();
                        if(autoScrollOn) {
                            present_vp.left  = time_base_vp.left;
                            present_vp.right = time_base_vp.right;
                        }
                        PointArrayWrapper onscreen;
                        if(dispModes[i]==ChDispModes.OFF) {
                            // Feed in empty dataset if we don't want to display
                            onscreen = new PointArrayWrapper();
                        } else {
                            onscreen = axisValueHelpers[i].getValuesInViewport(present_vp);
                        }

                        setLineValues(i,onscreen.backing);

                        Viewport fitsTheData = onscreen.getBoundingViewport();
                        switch(dispModes[i]) {
                            case AUTO:
                                present_vp.bottom = fitsTheData.bottom;
                                present_vp.top    = fitsTheData.top;
                                break;
                            case MANUAL:
                            case OFF:
                            default:
                                break;

                        }

                        float range = fitsTheData.top-fitsTheData.bottom;
                        fitsTheData.bottom  -= 10*range;
                        fitsTheData.top     += 10*range;
                        mChart[i].setMaximumViewport(fitsTheData);
                        mChart[i].setCurrentViewport(present_vp);
                    }
                }
                // Here we inset the content rects between the two overlapping graphs, otherwise
                // they will overlap eachother's axes
                Display display = getWindowManager().getDefaultDisplay();
                Point size = new Point();
                display.getSize(size);
                //Rect content_rect = new Rect(120,0,size.x-120,size.y-120);
                //maxContentRect.set(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom);
                //mChart[0].getChartComputator().setContentRect(w,h,l,t,r,b);
                //mChart[0].getChartComputator().setContentRect(size.x,size.y,120,0,120,120);
                //mChart[1].getChartComputator().setContentRect(size.x,size.y,120,0,120,120);
                //mChart[0].getChartComputator().setContentRect(mChart[0].getWidth(),);
                // Left chart needs inset on right
                // The negative signs below were worked out empirically... can't figure out why some seem to be reversed and others not
                //lcc.insetContentRect(0,0,-rcc.getContentRectMinusAxesMargins().right,0);
                //rcc.insetContentRect(lcc.getContentRectMinusAxesMargins().left,0,0,-lcc.getContentRectMinusAxesMargins().bottom);

                //Force chart to draw current data again
                mChart[0].setLineChartData(mChart[0].getLineChartData());
                mChart[1].setLineChartData(mChart[1].getLineChartData());
                
                mChart[0].getChartComputator().insetContentRect(0,0,120,0);
                mChart[1].getChartComputator().insetContentRect(120,0,0,120);
            }
        });
    }

    @Override
    public void addPoint(final int series_n, final float x, final float y) {
        PointValue v = new PointValue(x,y);
        List<PointValue> l = new ArrayList<>();
        l.add(v);
        addPoints(series_n, l);
    }

    @Override
    public void addPoints(final int series_n, final List<PointValue> new_values) {
        try {
            //Set new data on the graph
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    for(PointValue p:new_values) {
                        axisValueHelpers[series_n].backing.add(p);
                    }
                }
            });
        } catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
            Log.e(TAG, "No series found at index " + series_n);
        }
    }

    @Override
    public void setXAxisTitle(String title) {
        xAxis.setName(title);
    }

    @Override
    public void setYAxisTitle(int series_n, String title) {
        if (series_n == 0) {
            yAxisLeft.setName(title);
        } else if(series_n == 1) {
            yAxisRight.setName(title);
        }
    }

    /////////////////////////
    // MooshimeterDelegate methods
    /////////////////////////

    @Override
    public void onInit() {}
    @Override
    public void onDisconnect() {
        transitionToActivity(mMeter,ScanActivity.class);
    }
    @Override
    public void onRssiReceived(int rssi) {}
    @Override
    public void onBatteryVoltageReceived(float voltage) {}
    @Override
    public void onSampleReceived(double timestamp_utc, final MooshimeterControlInterface.Channel c, MeterReading val) {
        if(bufferModeOn || !playing) {
            Log.d(TAG,"Received a trailing sample");
            return;
        }
        if(c== MooshimeterControlInterface.Channel.MATH) {
            // Ignore for now
            return;
        }
        float dt = (float) (timestamp_utc - time_start);
        addPoint(c.ordinal(), dt, val.value);
    }
    double buf0_t = 0;
    @Override
    public void onBufferReceived(double timestamp_utc, final MooshimeterControlInterface.Channel c, float dt, float[] val) {
        if(!bufferModeOn || !playing) {
            Log.d(TAG,"Received a trailing buffer");
            return;
        }
        // There is some awkwardness here because the timestamps correspond
        // to when the buffers were received, not when they were taken.
        // We know for a fact the sampling was simultaneous, so we'll ignore channel1's
        // timestamp and just use ch0.
        if(c == MooshimeterControlInterface.Channel.CH1) {
            buf0_t = (timestamp_utc - dt*val.length)-time_start;
        }
        double t = buf0_t;
        List<PointValue> l = new ArrayList<>(val.length);
        for(float v:val) {
            l.add(new PointValue((float)t,v));
            t += dt;
        }
        addPoints(c.ordinal(), l);
        if(c== MooshimeterControlInterface.Channel.CH2) {
            // This is the second buffer
            maxNumberOfPointsOnScreen = val.length;
        }
    }
    @Override
    public void onSampleRateChanged(int i, int sample_rate_hz) {}
    @Override
    public void onBufferDepthChanged(int i, int buffer_depth) {}
    @Override
    public void onLoggingStatusChanged(boolean on, int new_state, String message) {}
    @Override
    public void onRangeChange(final MooshimeterControlInterface.Channel c, MooshimeterDeviceBase.RangeDescriptor new_range) {}
    @Override
    public void onInputChange(final MooshimeterControlInterface.Channel c, MooshimeterDeviceBase.InputDescriptor descriptor) {}
    @Override
    public void onOffsetChange(MooshimeterControlInterface.Channel c, MeterReading offset) {

    }/*
    private class RightAxisFormatter extends SimpleAxisValueFormatter {
        @Override
        public int formatValueForAutoGeneratedAxis(char[] formattedValue, float value, int autoDecimalDigits) {
            //Scale back to the original value so that it can be shown on
            float scaledValue = rightToLeftHelper.invert(value);
            // WARNING: Due to a but in HelloCharts FloatUtil, trying to display too many digits causes
            // values to cap at INT_MAX.
            // Try to display 6 sigfigs
            int left_digits = (int)Math.log10((double)scaledValue);
            int right_digits = -1*(left_digits-6);
            right_digits = right_digits<0?0:right_digits;
            return super.formatValueForAutoGeneratedAxis(formattedValue, scaledValue, right_digits);
        }
    }*/
}