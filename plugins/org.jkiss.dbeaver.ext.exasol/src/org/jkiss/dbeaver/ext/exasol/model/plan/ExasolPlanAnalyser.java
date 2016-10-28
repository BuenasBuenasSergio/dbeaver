/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2016-2016 Karl Griesser (fullref@gmail.com)
 * Copyright (C) 2010-2016 Serge Rieder (serge@jkiss.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.ext.exasol.model.plan;

import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.exasol.model.ExasolDataSource;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.plan.DBCPlan;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Karl
 */
public class ExasolPlanAnalyser implements DBCPlan {

    private static final Log LOG = Log.getLog(ExasolPlanAnalyser.class);

    private ExasolDataSource dataSource;
    private String query;
    private List<ExasolPlanNode> rootNodes;

    public ExasolPlanAnalyser(ExasolDataSource dataSource, String query) {
        this.dataSource = dataSource;
        this.query = query;
    }

    @Override
    public String getQueryString() {
        return query;
    }

    @Override
    public Collection<ExasolPlanNode> getPlanNodes() {
        return rootNodes;
    }

    public void explain(DBCSession session)
        throws DBCException {
        rootNodes = new ArrayList<>();
        JDBCSession connection = (JDBCSession) session;
        boolean oldAutoCommit = false;
        try {
            oldAutoCommit = connection.getAutoCommit();
            if (oldAutoCommit)
                connection.setAutoCommit(false);

            //alter session
            JDBCUtils.executeSQL(connection, "ALTER SESSION SET PROFILE = 'ON'");

            //execute query
            JDBCUtils.executeSQL(connection, query);

            //alter session
            JDBCUtils.executeSQL(connection, "ALTER SESSION SET PROFILE = 'OFF'");

            //rollback in case of DML
            connection.rollback();

            //alter session
            JDBCUtils.executeSQL(connection, "FLUSH STATISTICS");
            connection.commit();

            //retrieve execute info
            try (JDBCPreparedStatement stmt = connection.prepareStatement("SELECT * FROM EXA_USER_PROFILE_LAST_DAY WHERE SESSION_ID = CURRENT_SESSION AND STMT_ID = (select max(stmt_id) from EXA_USER_PROFILE_LAST_DAY where sql_text = ?)")) {
	            stmt.setString(1, query);
	            try (JDBCResultSet dbResult = stmt.executeQuery()) {
		            while (dbResult.next()) {
		                ExasolPlanNode node = new ExasolPlanNode(null, dbResult);
		                rootNodes.add(node);
		            }
	            }
            }

        } catch (SQLException e) {
            throw new DBCException(e, session.getDataSource());
        } finally {

            //rollback changes because profile actually executes query and it could be INSERT/UPDATE
            try {
                connection.rollback();
                if (oldAutoCommit)
                    connection.setAutoCommit(true);
            } catch (SQLException e) {
                LOG.error("Error closing plan analyser", e);
            }
        }
    }

    public ExasolDataSource getDataSource() {
        return this.dataSource;
    }


}