package com.conveyal.datatools.editor.models.transit;

import com.conveyal.datatools.editor.models.Model;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.Lists;

import java.io.Serializable;
import java.util.List;

/**
 * Created by landon on 6/22/16.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public class Fare extends Model implements Cloneable, Serializable {
    public static final long serialVersionUID = 1;

    public String feedId;
    public String gtfsFareId;
    public String description;
    public Double price;
    public String currencyType;
    public Integer paymentMethod;
    public Integer transfers;
    public Integer transferDuration;
    public List fareRules  = Lists.newArrayList();

    public Fare() {};

    public Fare(com.conveyal.gtfs.model.FareAttribute fare, List<com.conveyal.gtfs.model.FareRule> rules, EditorFeed feed) {
        this.gtfsFareId = fare.fare_id;
        this.price = fare.price;
        this.currencyType = fare.currency_type;
        this.paymentMethod = fare.payment_method;
        this.transfers = fare.transfers;
        this.transferDuration = fare.transfer_duration;
        this.fareRules.addAll(rules);
        this.feedId = feed.id;
        inferName();
    }

    /**
     * Infer the name of this calendar
     */
    public void inferName () {
        StringBuilder sb = new StringBuilder(14);

        if (price != null)
            sb.append(price);
        if (currencyType != null)
            sb.append(currencyType);

        this.description = sb.toString();

        if (this.description.equals("") && this.gtfsFareId != null)
            this.description = gtfsFareId;
    }
}