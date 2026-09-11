const { merge } = require('webpack-merge');
const webpackProductionConfig = require('./webpack.prod.js');

module.exports = merge(webpackProductionConfig, {
  output: {
    path: '/exo-server/webapps/commons-exo-extension/',
  },
  mode: 'development',
  devtool: 'inline-source-map'
});
