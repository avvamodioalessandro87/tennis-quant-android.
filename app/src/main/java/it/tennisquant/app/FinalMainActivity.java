package it.tennisquant.app;

import android.os.Bundle;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FinalMainActivity extends FixedMainActivity {
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        String last=getSharedPreferences("tq",MODE_PRIVATE).getString("last_live_sync","");
        if(last.isEmpty()) status.setText("ATP · snapshot storico + verifica live obbligatoria ad ogni analisi");
        else status.setText("ATP · ultimo sync live: "+last+" · nuova verifica ad ogni analisi");
    }

    @Override ResolvedPlayer resolvePlayer(String query,String key)throws Exception{
        ResolvedPlayer p=super.resolvePlayer(query,key);
        String now=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.ITALY).format(new Date());
        getSharedPreferences("tq",MODE_PRIVATE).edit().putString("last_live_sync",now).apply();
        runOnUiThread(()->status.setText("ATP · dati live verificati: "+now+" · refresh ad ogni analisi"));
        return p;
    }
}
