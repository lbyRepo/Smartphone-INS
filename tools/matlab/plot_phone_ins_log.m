function plot_phone_ins_log(csvFile)
%PLOT_PHONE_INS_LOG Plot Smartphone INS CSV trial logs.
%
%   plot_phone_ins_log("phoneins_trial_YYYYMMDD_HHMMSS.csv")
%
% The Android app writes one CSV row per 100 Hz output frame. This script
% produces:
%   1) A 3x3 INS solution figure.
%   2) A 2x3 raw IMU figure.
%   3) A 2x3 INS-vs-GNSS position/velocity comparison figure.

if nargin < 1 || isempty(csvFile)
    [fileName, folder] = uigetfile('*.csv', 'Select Smartphone INS CSV log');
    if isequal(fileName, 0)
        return;
    end
    csvFile = fullfile(folder, fileName);
end

T = readtable(csvFile);

t = getTimeSeconds(T);
[~, trialName, ~] = fileparts(csvFile);
trialName = strrep(trialName, '_', '\_');

figure('Name', 'Smartphone INS solution', 'Color', 'w');
subplot(331); plotTile(t, T.ins_latitude_deg, 'Latitude', 'deg');
subplot(332); plotTile(t, T.ins_longitude_deg, 'Longitude', 'deg');
subplot(333); plotTile(t, T.ins_altitude_m, 'Altitude', 'm');
subplot(334); plotTile(t, T.ins_velocity_north_mps, 'Velocity North', 'm/s');
subplot(335); plotTile(t, T.ins_velocity_east_mps, 'Velocity East', 'm/s');
subplot(336); plotTile(t, T.ins_velocity_down_mps, 'Velocity Down', 'm/s');
subplot(337); plotTile(t, T.ins_roll_deg, 'Roll', 'deg');
subplot(338); plotTile(t, T.ins_pitch_deg, 'Pitch', 'deg');
subplot(339); plotTile(t, T.ins_yaw_deg, 'Yaw', 'deg');
safeSgtitle(['INS solution: ', trialName]);

figure('Name', 'Smartphone INS raw IMU', 'Color', 'w');
subplot(231); plotTile(t, T.imu_accel_x_mps2, 'Accelerometer X', 'm/s^2');
subplot(232); plotTile(t, T.imu_accel_y_mps2, 'Accelerometer Y', 'm/s^2');
subplot(233); plotTile(t, T.imu_accel_z_mps2, 'Accelerometer Z', 'm/s^2');
subplot(234); plotTile(t, T.imu_gyro_x_radps, 'Gyroscope X', 'rad/s');
subplot(235); plotTile(t, T.imu_gyro_y_radps, 'Gyroscope Y', 'rad/s');
subplot(236); plotTile(t, T.imu_gyro_z_radps, 'Gyroscope Z', 'rad/s');
safeSgtitle(['Raw IMU: ', trialName]);

figure('Name', 'Smartphone INS vs GNSS', 'Color', 'w');
subplot(231); plotCompareTile(t, T.ins_latitude_deg, T.gnss_latitude_deg, 'Latitude', 'deg');
subplot(232); plotCompareTile(t, T.ins_longitude_deg, T.gnss_longitude_deg, 'Longitude', 'deg');
subplot(233); plotCompareTile(t, T.ins_altitude_m, T.gnss_altitude_m, 'Altitude', 'm');
subplot(234); plotCompareTile(t, T.ins_velocity_north_mps, T.gnss_velocity_north_mps, 'Velocity North', 'm/s');
subplot(235); plotCompareTile(t, T.ins_velocity_east_mps, T.gnss_velocity_east_mps, 'Velocity East', 'm/s');
subplot(236); plotCompareTile(t, T.ins_velocity_down_mps, T.gnss_velocity_down_mps, 'Velocity Down', 'm/s');
safeSgtitle(['INS vs GNSS: ', trialName]);

fprintf("Loaded %d rows from %s\n", height(T), csvFile);
fprintf("Time span: %.3f s\n", max(t) - min(t));

end

function t = getTimeSeconds(T)
if ismember('monotonic_nanos', T.Properties.VariableNames)
    raw = double(T.monotonic_nanos);
    t = (raw - raw(1)) * 1e-9;
elseif ismember('unix_millis', T.Properties.VariableNames)
    raw = double(T.unix_millis);
    t = (raw - raw(1)) * 1e-3;
else
    t = (0:height(T)-1).';
end
end

function plotTile(t, y, titleText, yLabelText)
plot(t, y, 'LineWidth', 1.1);
grid on;
xlabel('Time (s)');
ylabel(yLabelText);
title(titleText);
end

function plotCompareTile(t, insValue, gnssValue, titleText, yLabelText)
plot(t, insValue, 'LineWidth', 1.2);
hold on;
plot(t, gnssValue, '.', 'MarkerSize', 7);
hold off;
grid on;
xlabel('Time (s)');
ylabel(yLabelText);
title(titleText);
legend('INS', 'GNSS', 'Location', 'best');
end

function safeSgtitle(titleText)
if exist('sgtitle', 'file') == 2
    sgtitle(titleText);
else
    annotation('textbox', [0 0.96 1 0.04], ...
        'String', titleText, ...
        'EdgeColor', 'none', ...
        'HorizontalAlignment', 'center', ...
        'FontWeight', 'bold');
end
end
