/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2011, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.engine.jdbc.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.hibernate.AssertionFailure;
import org.hibernate.ScrollMode;
import org.hibernate.cfg.Settings;
import org.hibernate.engine.jdbc.spi.LogicalConnectionImplementor;
import org.hibernate.engine.jdbc.spi.SqlExceptionHelper;
import org.hibernate.engine.jdbc.spi.StatementPreparer;

/**
 * @author Steve Ebersole
 * @author Lukasz Antoniak (lukasz dot antoniak at gmail dot com)
 * @author Brett Meyer
*/
class StatementPreparerImpl implements StatementPreparer {
	private JdbcCoordinatorImpl jdbcCoordinator;

	StatementPreparerImpl(JdbcCoordinatorImpl jdbcCoordinator) {
		this.jdbcCoordinator = jdbcCoordinator;
	}

	protected final Settings settings() {
		return jdbcCoordinator.sessionFactory().getSettings();
	}

	protected final Connection connection() {
		return logicalConnection().getConnection();
	}

	protected final LogicalConnectionImplementor logicalConnection() {
		return jdbcCoordinator.getLogicalConnection();
	}

	protected final SqlExceptionHelper sqlExceptionHelper() {
		return jdbcCoordinator.getTransactionCoordinator().getTransactionContext().getTransactionEnvironment()
				.getJdbcServices()
				.getSqlExceptionHelper();
	}
	
	@Override
	public Statement createStatement() {
		try {
			Statement statement = connection().createStatement();
			jdbcCoordinator.register( statement );
			return statement;
		}
		catch ( SQLException e ) {
			throw sqlExceptionHelper().convert( e, "could not create statement" );
		}
	}

	@Override
	public PreparedStatement prepareStatement(String sql) {
		return buildPreparedStatementPreparationTemplate( sql, false ).prepareStatement();
	}

	@Override
	public PreparedStatement prepareStatement(String sql, final boolean isCallable) {
		jdbcCoordinator.executeBatch();
		return buildPreparedStatementPreparationTemplate( sql, isCallable ).prepareStatement();
	}

	private StatementPreparationTemplate buildPreparedStatementPreparationTemplate(String sql, final boolean isCallable) {
		return new StatementPreparationTemplate( sql ) {
			@Override
			protected PreparedStatement doPrepare() throws SQLException {
				return isCallable
						? connection().prepareCall( sql )
						: connection().prepareStatement( sql );
			}
		};
	}

	private void checkAutoGeneratedKeysSupportEnabled() {
		if ( ! settings().isGetGeneratedKeysEnabled() ) {
			throw new AssertionFailure( "getGeneratedKeys() support is not enabled" );
		}
	}

	@Override
	public PreparedStatement prepareStatement(String sql, final int autoGeneratedKeys) {
		if ( autoGeneratedKeys == PreparedStatement.RETURN_GENERATED_KEYS ) {
			checkAutoGeneratedKeysSupportEnabled();
		}
		jdbcCoordinator.executeBatch();
		return new StatementPreparationTemplate( sql ) {
			public PreparedStatement doPrepare() throws SQLException {
				return connection().prepareStatement( sql, autoGeneratedKeys );
			}
		}.prepareStatement();
	}

	@Override
	public PreparedStatement prepareStatement(String sql, final String[] columnNames) {
		checkAutoGeneratedKeysSupportEnabled();
		jdbcCoordinator.executeBatch();
		return new StatementPreparationTemplate( sql ) {
			public PreparedStatement doPrepare() throws SQLException {
				return connection().prepareStatement( sql, columnNames );
			}
		}.prepareStatement();
	}

	@Override
	public PreparedStatement prepareQueryStatement(
			String sql,
			final boolean isCallable,
			final ScrollMode scrollMode) {
		if ( scrollMode != null && !scrollMode.equals( ScrollMode.FORWARD_ONLY ) ) {
			if ( ! settings().isScrollableResultSetsEnabled() ) {
				throw new AssertionFailure("scrollable result sets are not enabled");
			}
			PreparedStatement ps = new QueryStatementPreparationTemplate( sql ) {
				public PreparedStatement doPrepare() throws SQLException {
						return isCallable
								? connection().prepareCall(
								sql, scrollMode.toResultSetType(), ResultSet.CONCUR_READ_ONLY
						)
								: connection().prepareStatement(
								sql, scrollMode.toResultSetType(), ResultSet.CONCUR_READ_ONLY
						);
				}
			}.prepareStatement();
			jdbcCoordinator.registerLastQuery( ps );
			return ps;
		}
		else {
			PreparedStatement ps = new QueryStatementPreparationTemplate( sql ) {
				public PreparedStatement doPrepare() throws SQLException {
						return isCallable
								? connection().prepareCall( sql )
								: connection().prepareStatement( sql );
				}
			}.prepareStatement();
			jdbcCoordinator.registerLastQuery( ps );
			return ps;
		}
	}

	private abstract class StatementPreparationTemplate {
		protected final String sql;

		protected StatementPreparationTemplate(String sql) {
			this.sql = jdbcCoordinator.getTransactionCoordinator().getTransactionContext().onPrepareStatement( sql );
		}

		public PreparedStatement prepareStatement() {
			try {
				jdbcCoordinator.getLogicalConnection().getJdbcServices().getSqlStatementLogger().logStatement( sql );

				final PreparedStatement preparedStatement;
				try {
					jdbcCoordinator.getTransactionCoordinator().getTransactionContext().startPrepareStatement();
					preparedStatement = doPrepare();
					setStatementTimeout( preparedStatement );
				}
				finally {
					jdbcCoordinator.getTransactionCoordinator().getTransactionContext().endPrepareStatement();
				}
				postProcess( preparedStatement );
				return preparedStatement;
			}
			catch ( SQLException e ) {
				throw sqlExceptionHelper().convert( e, "could not prepare statement", sql );
			}
		}

		protected abstract PreparedStatement doPrepare() throws SQLException;

		public void postProcess(PreparedStatement preparedStatement) throws SQLException {
			jdbcCoordinator.register( preparedStatement );
			logicalConnection().notifyObserversStatementPrepared();
		}

		private void setStatementTimeout(PreparedStatement preparedStatement) throws SQLException {
			final int remainingTransactionTimeOutPeriod = jdbcCoordinator.determineRemainingTransactionTimeOutPeriod();
			if ( remainingTransactionTimeOutPeriod > 0 ) {
				preparedStatement.setQueryTimeout( remainingTransactionTimeOutPeriod );
			}
		}
	}

	private abstract class QueryStatementPreparationTemplate extends StatementPreparationTemplate {
		protected QueryStatementPreparationTemplate(String sql) {
			super( sql );
		}

		public void postProcess(PreparedStatement preparedStatement) throws SQLException {
			super.postProcess( preparedStatement );
			setStatementFetchSize( preparedStatement );
		}
	}

	private void setStatementFetchSize(PreparedStatement statement) throws SQLException {
		if ( settings().getJdbcFetchSize() != null ) {
			statement.setFetchSize( settings().getJdbcFetchSize() );
		}
	}

}
