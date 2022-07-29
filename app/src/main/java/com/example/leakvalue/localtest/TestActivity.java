package com.example.leakvalue.localtest;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.leakvalue.MiscUtils;
import com.example.leakvalue.R;
import com.example.leakvalue.ValueLeaker;
import com.example.leakvalue.ValueLeakerMaker;

public class TestActivity extends AppCompatActivity {

    private TestModel mTestModel;
    private ValueLeaker mValueLeaker;

    SharedPreferences mPreferences;
    CheckBox mUseTestService;
    Spinner mFactorySpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);
        mTestModel = new ViewModelProvider(this).get(TestModel.class);
        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mUseTestService = findViewById(R.id.use_test_service);
        mUseTestService.setChecked(mPreferences.getBoolean("use_test_service", false));
        mFactorySpinner = findViewById(R.id.leaker_factory);
        mFactorySpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new String[]{
                "makeHolderLeaker",
                "makeHolderLeakerWithRewind",
                "makeOwnedLeaker"
        }));
        mFactorySpinner.setSelection(mPreferences.getInt("leaker_factory", 0));
    }

    @Override
    protected void onDestroy() {
        mPreferences
                .edit()
                .putBoolean("use_test_service", mUseTestService.isChecked())
                .putInt("leaker_factory", mFactorySpinner.getSelectedItemPosition())
                .apply();
        super.onDestroy();
    }

    ValueLeaker makeSelectedLeaker(ValueLeakerMaker leakerMaker) throws ReflectiveOperationException, RemoteException {
        int leakSize = 56;
        switch (mFactorySpinner.getSelectedItemPosition()) {
            case 0:
                return leakerMaker.makeHolderLeaker(leakSize);
            case 1:
                return leakerMaker.makeHolderLeakerWithRewind(leakSize);
            case 2:
                return leakerMaker.makeOwnedLeaker(leakSize);
        }
        throw new IllegalStateException();
    }

    public void doStuff1(View view) throws ReflectiveOperationException, RemoteException {
        MiscUtils.allowHiddenApis();

        // Create Context for which will point to appropriate MediaSessionService implementation
        Context leakerContext;
        if (mUseTestService.isChecked()) {
            leakerContext = mTestModel.createTestContext();
        } else {
            leakerContext = this;
        }

        // Create ValueLeaker
        ValueLeakerMaker leakerMaker = new ValueLeakerMaker(leakerContext);
        mValueLeaker = makeSelectedLeaker(leakerMaker);

        // Alternate test: Check if runWithNestLevels works
        // leakerMaker.runWithNestLevels(3, i -> {
        //     Log.i("NestLevels", "LEVEL=" + i);
        // });
    }

    public void doStuff2(View view) throws RemoteException {
        mTestModel.startMockAnotherTransaction();
        Parcel leakedParcel = mValueLeaker.doLeak();
        if (leakedParcel != null) {
            Toast.makeText(this, "Leaked " + leakedParcel.dataSize() + " bytes", Toast.LENGTH_LONG).show();
        }
        mTestModel.finishMockAnotherTransaction();
    }
}
